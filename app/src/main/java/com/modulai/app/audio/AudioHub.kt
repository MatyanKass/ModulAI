package com.modulai.app.audio

import android.content.Context
import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.SpeechEvent
import com.modulai.core.runtime.SpeechParams
import com.modulai.core.runtime.SpeechToTextSession
import com.modulai.core.runtime.TextToSpeechSession
import com.modulai.core.runtime.TranscribeOptions
import com.modulai.core.runtime.Voice
import com.modulai.core.runtime.VoiceCloningSession
import com.modulai.core.runtime.VoiceKind
import com.modulai.engine.audio.OnnxTtsEngine
import com.modulai.engine.audio.SystemTtsEngine
import com.modulai.engine.audio.WhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** One entry of the voice picker: a voice together with the model that speaks it. */
data class VoiceOption(
    /** Directory of the neural model, or empty for the system speech service. */
    val modelPath: String,
    val modelTitle: String,
    /** Null stands for whatever voice the system picks for the language. */
    val voice: Voice?,
    /** Languages this voice can speak; empty when the system decides. */
    val languages: Set<String>,
    val adjustableSpeed: Boolean,
) {
    val key get() = "$modelPath|${voice?.id.orEmpty()}"
    val isSystem get() = modelPath.isEmpty()
}

enum class SpeakState { IDLE, PREPARING, PLAYING }

/**
 * Everything the app does with sound: recognition, synthesis, cloned voices, the microphone and
 * the speaker. Process-wide, like the download queue, so the chat and the audio screen share
 * loaded models and the user's choice of voice. Obtain it with [AudioHub.get].
 */
class AudioHub private constructor(private val context: Context) {
    private val whisper = WhisperEngine()
    private val onnxTts = OnnxTtsEngine()
    private val systemTts = SystemTtsEngine(context)

    val models = AudioModelStore(context, onnxTts)
    val clonedVoices = ClonedVoiceStore(context)

    private val prefs = context.getSharedPreferences("audio", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = MicRecorder(context)
    private val player = PcmPlayer()

    // Same reasoning as the text engine's default: half the cores, at most four.
    private val config = LoadConfig(Backend.CPU, threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4))

    private val sttMutex = Mutex()
    private var stt: SpeechToTextSession? = null
    private val ttsMutex = Mutex()
    private var tts: TextToSpeechSession? = null
    private var system: TextToSpeechSession? = null
    private val inUse = AtomicInteger()

    @Volatile
    private var idleJob: Job? = null

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val _micLevel = MutableStateFlow(0f)

    /** Loudness of the microphone while [listening], 0..1. */
    val micLevel: StateFlow<Float> = _micLevel

    private val _speakState = MutableStateFlow(SpeakState.IDLE)
    val speakState: StateFlow<SpeakState> = _speakState
    private val _speakError = MutableStateFlow<Throwable?>(null)

    /** Why the last [speak] call produced no sound; cleared by the next one. */
    val speakError: StateFlow<Throwable?> = _speakError
    private val _lastSpeech = MutableStateFlow<Pcm?>(null)

    /** The most recently synthesized sound, for replaying and saving. */
    val lastSpeech: StateFlow<Pcm?> = _lastSpeech

    // Guarded by the hub's monitor: the chat's tool loop may call in from a thread of its own.
    private var speakJob: Job? = null

    @Volatile
    private var speakGeneration = 0

    var recognitionLanguage: String
        get() = prefs.getString(RECOGNITION_LANGUAGE, TranscribeOptions.AUTO) ?: TranscribeOptions.AUTO
        set(value) = prefs.edit().putString(RECOGNITION_LANGUAGE, value).apply()

    var speechLanguage: String
        get() = prefs.getString(SPEECH_LANGUAGE, TranscribeOptions.AUTO) ?: TranscribeOptions.AUTO
        set(value) = prefs.edit().putString(SPEECH_LANGUAGE, value).apply()

    var speed: Float
        get() = prefs.getFloat(SPEED, 1f)
        set(value) = prefs.edit().putFloat(SPEED, value).apply()

    /** [VoiceOption.key] of the voice used by [speak]; null until the user picks one. */
    var voiceKey: String?
        get() = prefs.getString(VOICE, null)
        set(value) = prefs.edit().putString(VOICE, value).apply()

    var speechModelPath: String?
        get() = prefs.getString(SPEECH_MODEL, null)
        set(value) = prefs.edit().putString(SPEECH_MODEL, value).apply()

    /** The recognition model in use: the chosen one, or the largest installed. */
    fun speechModel(): ModelDescriptor? {
        val installed = models.speechToTextModels()
        return installed.find { it.path == speechModelPath } ?: installed.lastOrNull()
    }

    // ---- Speech to text

    /**
     * Recognizes speech in [pcm] and returns the text. [onEvent] sees progress and segments as
     * they come. Throws [AudioException] when no recognition model is installed.
     */
    suspend fun transcribe(
        pcm: Pcm,
        language: String = recognitionLanguage,
        translate: Boolean = false,
        onEvent: (SpeechEvent) -> Unit = {},
    ): String = whileInUse {
        val session = sttMutex.withLock {
            val model = speechModel() ?: throw AudioException(AudioException.Reason.NO_SPEECH_MODEL)
            stt?.takeIf { it.model.path == model.path } ?: run {
                stt?.close()
                stt = null
                whisper.load(model, config).also { stt = it }
            }
        }
        var text = ""
        session.transcribe(pcm, TranscribeOptions(language, translate, config.threads)).collect {
            onEvent(it)
            if (it is SpeechEvent.Done) text = it.text
        }
        text
    }

    /**
     * Records the microphone until [stopListening], [maxSeconds], or a pause in speech. The caller
     * must hold the RECORD_AUDIO permission (see [hasMicPermission]).
     */
    suspend fun record(maxSeconds: Int = 120, stopOnSilence: Boolean = false): Pcm {
        _listening.value = true
        try {
            return recorder.record(maxSeconds, stopOnSilence) { _micLevel.value = it }
        } finally {
            _listening.value = false
        }
    }

    /** Ends the recording started by [record] or [transcribeFromMic]; what was captured is kept. */
    fun stopListening() = recorder.stop()

    /** Voice input in one call: listen until the speaker pauses, then return what was said. */
    suspend fun transcribeFromMic(
        language: String = recognitionLanguage,
        maxSeconds: Int = 30,
        stopOnSilence: Boolean = true,
    ): String {
        // Checked first so that nobody talks to a microphone that cannot be understood.
        if (speechModel() == null) throw AudioException(AudioException.Reason.NO_SPEECH_MODEL)
        return transcribe(record(maxSeconds, stopOnSilence), language)
    }

    // ---- Text to speech

    /** All voices that can speak right now: neural presets, cloned voices, then system voices. */
    suspend fun voiceOptions(): List<VoiceOption> {
        val options = ArrayList<VoiceOption>()
        for (model in models.textToSpeechModels()) {
            val info = onnxTts.inspect(model) ?: continue
            val voices = info.voices + if (info.cloning) clonedVoices.list() else emptyList()
            voices.mapTo(options) { VoiceOption(model.path, model.id, it, info.languages, info.adjustableSpeed) }
        }
        val service = try {
            systemSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (service != null) {
            options += VoiceOption("", "", null, emptySet(), true)
            // Speech services list hundreds of voices; only the user's languages are worth showing.
            val wanted = setOf("ru", "en", Locale.getDefault().language)
            service.voices.filter { v -> v.languages.any { it in wanted } }
                .mapTo(options) { VoiceOption("", "", it, it.languages, true) }
        }
        return options
    }

    /** The voice [speak] uses: the saved choice if it still exists, else the first available. */
    fun pickVoice(options: List<VoiceOption>): VoiceOption? = options.find { it.key == voiceKey } ?: options.firstOrNull()

    suspend fun synthesize(
        text: String,
        option: VoiceOption? = null,
        language: String = speechLanguage,
        speed: Float = this.speed,
    ): Pcm = whileInUse {
        val chosen = option ?: pickVoice(voiceOptions()) ?: throw AudioException(AudioException.Reason.NO_VOICE)
        val plain = plainForSpeech(text)
        val session = sessionFor(chosen.modelPath)
        session.synthesize(plain, SpeechParams(chosen.voice, resolveLanguage(plain, language, chosen.languages), speed))
            .also { _lastSpeech.value = it }
    }

    /**
     * Reads [text] aloud in the selected voice and returns at once. Progress shows in
     * [speakState], failures in [speakError]; a new call replaces the one in flight.
     */
    fun speak(text: String, option: VoiceOption? = null) = startSpeaking { synthesize(text, option) }

    /** Plays [lastSpeech] again. */
    fun replay() {
        val pcm = _lastSpeech.value ?: return
        startSpeaking { pcm }
    }

    /** Synthesizes in the caller's coroutine, then plays in the background. Returns the sound. */
    suspend fun speakAndReturn(text: String): Pcm {
        val pcm = synthesize(text)
        startSpeaking { pcm }
        return pcm
    }

    @Synchronized
    fun stopSpeaking() {
        speakJob?.cancel()
    }

    @Synchronized
    private fun startSpeaking(prepare: suspend () -> Pcm) {
        speakJob?.cancel()
        val generation = ++speakGeneration
        _speakError.value = null
        _speakState.value = SpeakState.PREPARING
        speakJob = scope.launch {
            try {
                val pcm = prepare()
                _speakState.value = SpeakState.PLAYING
                player.play(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == speakGeneration) _speakError.value = e
            } finally {
                // A replaced job must not reset the state its successor has already set.
                if (generation == speakGeneration) _speakState.value = SpeakState.IDLE
            }
        }
    }

    // ---- Voice cloning

    /** The installed model that can clone voices, if any. */
    fun cloningModel(): ModelDescriptor? = models.textToSpeechModels().firstOrNull { onnxTts.inspect(it)?.cloning == true }

    /** Seconds of a reference recording the cloning model listens to; 0 without such a model. */
    fun cloneReferenceSeconds(): Int = cloningModel()?.let { onnxTts.inspect(it)?.cloneReferenceSeconds } ?: 0

    /** Makes a voice out of [reference] and adds it to [clonedVoices]. */
    suspend fun cloneVoice(reference: Pcm, name: String): Voice = whileInUse {
        val model = cloningModel() ?: throw AudioException(AudioException.Reason.NO_CLONING_MODEL)
        if (reference.durationMs < MIN_REFERENCE_MS) throw AudioException(AudioException.Reason.SAMPLE_TOO_SHORT)
        val session = sessionFor(model.path) as? VoiceCloningSession
            ?: throw AudioException(AudioException.Reason.NO_CLONING_MODEL)
        clonedVoices.add(name) { file -> session.cloneVoice(reference, name, file) }
    }

    // ---- Sessions

    private suspend fun sessionFor(modelPath: String): TextToSpeechSession {
        if (modelPath.isEmpty()) return systemSession()
        return ttsMutex.withLock {
            tts?.takeIf { it.model.path == modelPath } ?: run {
                tts?.close()
                tts = null
                val model = models.textToSpeechModels().find { it.path == modelPath }
                    ?: throw AudioException(AudioException.Reason.NO_VOICE)
                onnxTts.load(model, config).also { tts = it }
            }
        }
    }

    private suspend fun systemSession(): TextToSpeechSession = ttsMutex.withLock {
        system ?: systemTts.load(SystemTtsEngine.MODEL, config).also { system = it }
    }

    /** Forgets the model at [path] before its files are deleted. */
    suspend fun unload(path: String) {
        sttMutex.withLock {
            if (stt?.model?.path == path) {
                stt?.close()
                stt = null
            }
        }
        ttsMutex.withLock {
            if (tts?.model?.path == path) {
                tts?.close()
                tts = null
            }
        }
    }

    /**
     * Neural models hold up to a gigabyte, so they are dropped once nothing has used them for a
     * while. Work in progress holds the timer off; the countdown starts when the last job ends.
     */
    private suspend fun <T> whileInUse(block: suspend () -> T): T {
        inUse.incrementAndGet()
        idleJob?.cancel()
        try {
            return block()
        } finally {
            if (inUse.decrementAndGet() == 0) {
                idleJob = scope.launch {
                    delay(IDLE_RELEASE_MS)
                    // Checked under each lock: a job that has just begun cannot have its session yet.
                    sttMutex.withLock {
                        if (inUse.get() == 0) {
                            stt?.close()
                            stt = null
                        }
                    }
                    ttsMutex.withLock {
                        if (inUse.get() == 0) {
                            tts?.close()
                            tts = null
                        }
                    }
                }
            }
        }
    }

    private fun resolveLanguage(text: String, requested: String, supported: Set<String>): String {
        val wanted = if (requested == TranscribeOptions.AUTO) guessLanguage(text) else requested
        return when {
            supported.isEmpty() || wanted in supported -> wanted
            "en" in supported -> "en"
            else -> supported.first()
        }
    }

    companion object {
        private const val RECOGNITION_LANGUAGE = "recognition_language"
        private const val SPEECH_LANGUAGE = "speech_language"
        private const val SPEED = "speed"
        private const val VOICE = "voice"
        private const val SPEECH_MODEL = "speech_model"
        private const val IDLE_RELEASE_MS = 5 * 60_000L
        private const val MIN_REFERENCE_MS = 3_000L

        @Volatile
        private var instance: AudioHub? = null

        fun get(context: Context): AudioHub = instance ?: synchronized(this) {
            instance ?: AudioHub(context.applicationContext).also { instance = it }
        }
    }
}

/** Russian for mostly Cyrillic text (Ukrainian when its own letters show up), English otherwise. */
internal fun guessLanguage(text: String): String {
    var cyrillic = 0
    var latin = 0
    var ukrainian = 0
    for (c in text) {
        when {
            c in "іїєґІЇЄҐ" -> ukrainian++
            c in 'Ѐ'..'ӿ' -> cyrillic++
            c in 'a'..'z' || c in 'A'..'Z' -> latin++
        }
    }
    return when {
        cyrillic + ukrainian <= latin -> "en"
        ukrainian > 0 -> "uk"
        else -> "ru"
    }
}

/** Removes what a chat model writes for the eye and a voice should not read: markup, code, links. */
internal fun plainForSpeech(text: String): String = text
    .replace(CODE_BLOCK, " ")
    .replace(MARKDOWN_LINK, "$1")
    .replace(URL, " ")
    .replace(MARKUP, "")
    .replace(BLANKS, " ")
    .trim()

private val CODE_BLOCK = Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL)
private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val URL = Regex("""https?://\S+""")
private val MARKUP = Regex("""[*_`#>|~]+""")
private val BLANKS = Regex("""[ \t]+""")

/** Voices the user made from recordings, kept under `audio-voices/` in the app's external files. */
class ClonedVoiceStore(context: Context) {
    private val dir = (context.getExternalFilesDir("audio-voices") ?: File(context.filesDir, "audio-voices")).apply { mkdirs() }

    fun list(): List<Voice> = dir.listFiles { f -> f.isFile && f.name.endsWith(PROFILE) }.orEmpty()
        .sortedBy { it.name }
        .map { Voice(it.nameWithoutExtension, nameFile(it).takeIf(File::isFile)?.readText() ?: it.nameWithoutExtension, VoiceKind.CLONED, path = it.path) }

    /** [create] writes the profile into the file it is given; the name is recorded beside it. */
    suspend fun add(name: String, create: suspend (File) -> Voice): Voice {
        val file = File(dir, "${System.currentTimeMillis()}$PROFILE")
        val voice = create(file)
        nameFile(file).writeText(name)
        return voice
    }

    fun delete(voice: Voice) {
        val file = File(voice.path ?: return)
        file.delete()
        nameFile(file).delete()
    }

    private fun nameFile(profile: File) = File(profile.parentFile, profile.nameWithoutExtension + ".name")

    private companion object {
        const val PROFILE = ".voice"
    }
}
