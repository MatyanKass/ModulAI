package com.modulai.engine.audio

import ai.onnxruntime.OrtException
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
import com.modulai.core.runtime.VoiceCloningSession
import com.modulai.core.runtime.VoiceKind
import com.modulai.core.runtime.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Neural speech synthesis on ONNX Runtime. A model is a directory; which pipeline runs it is
 * decided by the files inside: Supertonic 3 (preset voices) or Chatterbox Multilingual
 * (voice cloning).
 */
class OnnxTtsEngine : Engine {
    override val id = "onnx-tts"
    override val capabilities = setOf(Capability.TEXT_TO_SPEECH)
    override val backends = setOf(Backend.CPU)

    // One model works at a time: two would compete for the same cores and double the memory peak.
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "onnx-tts").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    override fun supports(model: ModelDescriptor) =
        model.capability == Capability.TEXT_TO_SPEECH && model.format == ModelFormat.ONNX && kindOf(File(model.path)) != null

    override suspend fun load(model: ModelDescriptor, config: LoadConfig): TextToSpeechSession = withContext(dispatcher) {
        val dir = File(model.path)
        val kind = kindOf(dir)
        val info = inspect(model)
        if (kind == null || info == null) throw SpeechException("${model.id} is not a speech model this app can run")
        try {
            when (kind) {
                Kind.SUPERTONIC -> SupertonicSession(Supertonic(dir, config.threads), model, info, dispatcher)
                Kind.CHATTERBOX -> ChatterboxSession(Chatterbox(dir, config.threads), ChatterboxFiles(dir), model, info, dispatcher)
            }
        } catch (e: OrtException) {
            throw SpeechException("Could not load ${model.id}: ${e.message}")
        }
    }

    /**
     * What an installed model offers, read from its files without loading it. Null when the
     * directory is not a model this engine runs.
     */
    fun inspect(model: ModelDescriptor): TtsModelInfo? {
        val dir = File(model.path)
        return when (kindOf(dir)) {
            Kind.SUPERTONIC -> TtsModelInfo(
                Supertonic.voiceIds(dir).map { Voice("${model.id}:$it", it, VoiceKind.PRESET) },
                Supertonic.LANGUAGES, cloneReferenceSeconds = 0, adjustableSpeed = true,
            )
            Kind.CHATTERBOX -> TtsModelInfo(
                if (ChatterboxFiles(dir).hasStockVoice) listOf(Voice("${model.id}:$STOCK_VOICE", STOCK_VOICE, VoiceKind.PRESET)) else emptyList(),
                Chatterbox.LANGUAGES, cloneReferenceSeconds = Chatterbox.REFERENCE_SECONDS, adjustableSpeed = false,
            )
            null -> null
        }
    }

    private enum class Kind { SUPERTONIC, CHATTERBOX }

    companion object {
        /** Name of the one voice Chatterbox ships with; interfaces show their own wording for it. */
        const val STOCK_VOICE = "default"
    }

    private fun kindOf(dir: File) = when {
        File(dir, Supertonic.REQUIRED_FILE).isFile -> Kind.SUPERTONIC
        File(dir, Chatterbox.REQUIRED_FILE).isFile -> Kind.CHATTERBOX
        else -> null
    }
}

data class TtsModelInfo(
    val voices: List<Voice>,
    val languages: Set<String>,
    /**
     * How much reference speech the model uses to copy a voice, or 0 when it cannot clone.
     * Sessions of cloning models are [VoiceCloningSession]s.
     */
    val cloneReferenceSeconds: Int,
    val adjustableSpeed: Boolean,
) {
    val cloning get() = cloneReferenceSeconds > 0
}

/** The stock voice of Chatterbox ships as a recording; its profile is computed once and kept. */
private class ChatterboxFiles(dir: File) {
    val stockSample = File(dir, "default_voice.wav")
    val stockProfile = File(dir, "default_voice.voice")
    val hasStockVoice get() = stockSample.isFile || stockProfile.isFile
}

/** Runs [block] on the engine thread with a cancellation probe, translating runtime failures. */
private suspend fun <T> CoroutineDispatcher.inference(block: (checkCancelled: () -> Unit) -> T): T =
    withContext(this) {
        val context = coroutineContext
        try {
            block { context.ensureActive() }
        } catch (e: OrtException) {
            throw SpeechException(e.message ?: "Speech synthesis failed")
        }
    }

private class SupertonicSession(
    private val engine: Supertonic,
    override val model: ModelDescriptor,
    info: TtsModelInfo,
    private val dispatcher: CoroutineDispatcher,
) : TextToSpeechSession {
    override val backend = Backend.CPU
    override val languages = info.languages
    override val adjustableSpeed = info.adjustableSpeed
    override val voices = info.voices

    override suspend fun synthesize(text: String, params: SpeechParams): Pcm {
        val voice = params.voice?.takeIf { v -> voices.any { it.id == v.id } } ?: voices.first()
        return dispatcher.inference { check ->
            engine.synthesize(text, params.language, voice.name, params.speed.coerceIn(0.5f, 2f), check)
        }
    }

    override fun close() {
        dispatcher.dispatch(EmptyCoroutineContext) { engine.close() }
    }
}

private class ChatterboxSession(
    private val engine: Chatterbox,
    private val files: ChatterboxFiles,
    override val model: ModelDescriptor,
    info: TtsModelInfo,
    private val dispatcher: CoroutineDispatcher,
) : VoiceCloningSession {
    override val backend = Backend.CPU
    override val languages = info.languages
    override val adjustableSpeed = info.adjustableSpeed
    override val referenceSeconds = info.cloneReferenceSeconds
    override val voices = info.voices

    private val profiles = HashMap<String, VoiceProfile>()

    override suspend fun synthesize(text: String, params: SpeechParams): Pcm {
        val voice = params.voice?.takeIf { it.kind == VoiceKind.CLONED && it.path != null } ?: voices.firstOrNull()
            ?: throw SpeechException("This model has no voice of its own: create one from a recording first")
        return dispatcher.inference { check ->
            val profile = profiles.getOrPut(voice.id) { profileOf(voice) }
            check()
            engine.synthesize(text, params.language, profile, check)
        }
    }

    override suspend fun cloneVoice(reference: Pcm, name: String, target: File): Voice = dispatcher.inference {
        engine.encodeVoice(reference).write(target)
        Voice(target.nameWithoutExtension, name, VoiceKind.CLONED, path = target.path)
    }

    private fun profileOf(voice: Voice): VoiceProfile {
        val path = voice.path
        if (path != null) {
            val file = File(path)
            if (!file.isFile) throw SpeechException("The voice \"${voice.name}\" was deleted")
            return VoiceProfile.read(file)
        }
        if (!files.stockProfile.isFile) engine.encodeVoice(Wav.read(files.stockSample)).write(files.stockProfile)
        return VoiceProfile.read(files.stockProfile)
    }

    override fun close() {
        dispatcher.dispatch(EmptyCoroutineContext) { engine.close() }
    }
}
