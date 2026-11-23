package com.modulai.engine.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.Engine
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.ModelFormat
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.SpeechException
import com.modulai.core.runtime.SpeechParams
import com.modulai.core.runtime.TextToSpeechSession
import com.modulai.core.runtime.Voice
import com.modulai.core.runtime.VoiceKind
import com.modulai.core.runtime.Wav
import com.modulai.core.runtime.splitForSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The speech service installed on the device (Google, Xiaomi, RHVoice, ...), wrapped so that it
 * looks like any other speech model. It needs no download and speaks whatever languages the
 * device has voices for, which makes it the fallback when no neural model is installed.
 */
class SystemTtsEngine(context: Context) : Engine {
    private val context = context.applicationContext

    override val id = "system-tts"
    override val capabilities = setOf(Capability.TEXT_TO_SPEECH)
    override val backends = setOf(Backend.CPU)

    override fun supports(model: ModelDescriptor) = model.id == MODEL.id && model.path.isEmpty()

    override suspend fun load(model: ModelDescriptor, config: LoadConfig): TextToSpeechSession =
        withContext(Dispatchers.Main) {
            var tts: TextToSpeech? = null
            val status = suspendCancellableCoroutine { continuation ->
                tts = TextToSpeech(context) { if (continuation.isActive) continuation.resume(it) }
                continuation.invokeOnCancellation { tts?.shutdown() }
            }
            val ready = tts
            if (status != TextToSpeech.SUCCESS || ready == null) {
                ready?.shutdown()
                throw SpeechException("The device has no working speech service")
            }
            SystemTtsSession(ready, model, File(context.cacheDir, "system-tts").apply { mkdirs() })
        }

    companion object {
        /** There is no file behind the system voices; this descriptor stands in for one. */
        val MODEL = ModelDescriptor("system-tts", "", ModelFormat.ONNX, Capability.TEXT_TO_SPEECH, 0)
    }
}

private class SystemTtsSession(
    private val tts: TextToSpeech,
    override val model: ModelDescriptor,
    private val scratch: File,
) : TextToSpeechSession {
    override val backend = Backend.CPU
    override val languages = emptySet<String>()
    override val adjustableSpeed = true

    private val platformVoices = try {
        tts.voices.orEmpty()
    } catch (e: Exception) {
        // Some vendor services throw here instead of returning an empty set.
        emptySet()
    }.filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }

    override val voices = platformVoices
        .sortedWith(compareBy({ it.locale.toLanguageTag() }, { -it.quality }, { it.name }))
        .map { Voice(it.name, "${it.locale.displayName} · ${it.name}", VoiceKind.SYSTEM, setOf(it.locale.language)) }

    // The service renders one utterance at a time and reports back through a single listener.
    private val mutex = Mutex()
    private val counter = AtomicInteger()

    override suspend fun synthesize(text: String, params: SpeechParams): Pcm = mutex.withLock {
        val chosen = params.voice?.let { v -> platformVoices.firstOrNull { it.name == v.id } }
        if (chosen != null) {
            tts.voice = chosen
        } else {
            val result = tts.setLanguage(Locale.forLanguageTag(params.language))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw SpeechException("The device has no voice for \"${params.language}\"")
            }
        }
        tts.setSpeechRate(params.speed.coerceIn(0.5f, 2f))
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val parts = splitForSpeech(text, limit).map { render(it) }
        if (parts.isEmpty()) throw SpeechException("There is no text to speak")
        Pcm.concat(parts)
    }

    private suspend fun render(text: String): Pcm {
        val id = "modulai-${counter.incrementAndGet()}"
        val file = File(scratch, "$id.wav")
        try {
            suspendCancellableCoroutine { continuation ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit

                    override fun onDone(utteranceId: String) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

                    override fun onError(utteranceId: String, errorCode: Int) {
                        if (utteranceId == id && continuation.isActive) {
                            continuation.resumeWithException(SpeechException("The speech service failed (code $errorCode)"))
                        }
                    }
                })
                continuation.invokeOnCancellation { tts.stop() }
                if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS && continuation.isActive) {
                    continuation.resumeWithException(SpeechException("The speech service refused the text"))
                }
            }
            return withContext(Dispatchers.IO) {
                try {
                    Wav.read(file)
                } catch (e: IllegalArgumentException) {
                    throw SpeechException("The speech service produced no usable audio")
                }
            }
        } finally {
            file.delete()
        }
    }

    override fun close() {
        tts.shutdown()
    }
}
