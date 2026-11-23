package com.modulai.engine.audio

import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.Engine
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.ModelFormat
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.SpeechEvent
import com.modulai.core.runtime.SpeechException
import com.modulai.core.runtime.SpeechSegment
import com.modulai.core.runtime.SpeechToTextSession
import com.modulai.core.runtime.TranscribeOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

/** Speech recognition with whisper.cpp. Models are single ggml `.bin` files. */
class WhisperEngine : Engine {
    override val id = "whisper.cpp"
    override val capabilities = setOf(Capability.SPEECH_TO_TEXT)
    override val backends = setOf(Backend.CPU)

    // Whisper contexts are not thread-safe; every native call goes through this thread.
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "whisper").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    // ModelFormat has no entry for ggml's older container, so whisper files travel as GGUF.
    override fun supports(model: ModelDescriptor) =
        model.capability == Capability.SPEECH_TO_TEXT && model.format == ModelFormat.GGUF

    override suspend fun load(model: ModelDescriptor, config: LoadConfig): SpeechToTextSession =
        withContext(dispatcher) {
            missingCpuFeatures()?.let {
                throw SpeechException("This processor lacks $it, which the speech recognizer needs")
            }
            if (!File(model.path).isFile) throw SpeechException("Model file not found: ${model.path}")
            val handle = WhisperNative.load(model.path)
            if (handle == 0L) {
                val reason = WhisperNative.lastError().ifBlank { "not a whisper model" }
                throw SpeechException("Could not load ${model.id}: $reason")
            }
            WhisperSession(handle, model, dispatcher, config.threads)
        }

    /**
     * The Android library is compiled for ARMv8.2 with dot product and half-float arithmetic.
     * Returns the name of a missing extension, or null when the code can run here.
     */
    private fun missingCpuFeatures(): String? {
        if (System.getProperty("os.arch") != "aarch64") return null
        val features = try {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(' ')
            }
        } catch (e: Exception) {
            null
        } ?: return null
        return when {
            "asimddp" !in features -> "the dot product extension"
            "asimdhp" !in features -> "half-precision arithmetic"
            else -> null
        }
    }
}

private class WhisperSession(
    private var handle: Long,
    override val model: ModelDescriptor,
    private val dispatcher: CoroutineDispatcher,
    private val defaultThreads: Int,
) : SpeechToTextSession {
    override val backend = Backend.CPU
    override val multilingual = WhisperNative.isMultilingual(handle)

    // Guards the cancel flag: it may only be raised while a call is running, and is lowered
    // before the next one starts, so a late cancel() cannot poison the following call.
    private val lock = Any()
    private var running = false

    override fun transcribe(pcm: Pcm, options: TranscribeOptions): Flow<SpeechEvent> = callbackFlow {
        val job = launch(dispatcher) {
            val h = handle
            if (h == 0L) throw SpeechException("The recognizer is closed")
            val samples = pcm.resampled(SAMPLE_RATE).samples
            val text = StringBuilder()
            val pending = Utf8Joiner()
            synchronized(lock) {
                WhisperNative.clearCancel(h)
                running = true
            }
            val code = try {
                WhisperNative.transcribe(
                    h, samples, options.language, options.translate,
                    if (options.threads > 0) options.threads else defaultThreads,
                    object : WhisperCallback {
                        override fun onProgress(percent: Int) {
                            trySend(SpeechEvent.Progress(percent))
                        }

                        override fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray) {
                            val piece = pending.append(utf8)
                            if (piece.isEmpty()) return
                            text.append(piece)
                            trySend(SpeechEvent.Segment(SpeechSegment(startMs, endMs, piece.trim())))
                        }
                    },
                )
            } finally {
                synchronized(lock) { running = false }
            }
            when (code) {
                WhisperNative.DONE, WhisperNative.CANCELLED -> trySend(
                    SpeechEvent.Done(
                        text.toString().trim(), WhisperNative.detectedLanguage(h), code == WhisperNative.CANCELLED,
                    ),
                )
                WhisperNative.ERR_LANGUAGE -> throw SpeechException("Unknown language: ${options.language}")
                else -> throw SpeechException(WhisperNative.lastError().ifBlank { "Speech recognition failed" })
            }
        }
        job.invokeOnCompletion { close(it) }
        // The native loop cannot be interrupted, so ask it to stop and let the job finish.
        awaitClose { cancelRunning() }
    }.buffer(Channel.UNLIMITED)

    private fun cancelRunning() = synchronized(lock) {
        if (running && handle != 0L) WhisperNative.cancel(handle)
    }

    override fun close() {
        val h = handle
        if (h == 0L) return
        cancelRunning()
        handle = 0
        // Freed on the whisper thread so it waits for a running transcription to return.
        dispatcher.dispatch(EmptyCoroutineContext) { WhisperNative.free(h) }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/**
 * Whisper can end a segment in the middle of a multi-byte character. The incomplete tail is
 * held back and prepended to the next segment.
 */
internal class Utf8Joiner {
    private var carry = ByteArray(0)

    fun append(bytes: ByteArray): String {
        val all = carry + bytes
        val cut = completeLength(all)
        carry = all.copyOfRange(cut, all.size)
        return String(all, 0, cut, Charsets.UTF_8)
    }

    private fun completeLength(b: ByteArray): Int {
        var i = b.size - 1
        var back = 0
        // Step over at most three continuation bytes to find the lead byte of the last character.
        while (i >= 0 && back < 3 && (b[i].toInt() and 0xC0) == 0x80) {
            i--
            back++
        }
        if (i < 0) return b.size
        val lead = b[i].toInt() and 0xFF
        val need = when {
            lead >= 0xF0 -> 4
            lead >= 0xE0 -> 3
            lead >= 0xC0 -> 2
            else -> 1
        }
        return if (b.size - i < need) i else b.size
    }
}
