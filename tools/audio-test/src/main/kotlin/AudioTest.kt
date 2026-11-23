package com.modulai.audiotest

import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.ModelFormat
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.SpeechEvent
import com.modulai.core.runtime.SpeechException
import com.modulai.core.runtime.SpeechParams
import com.modulai.core.runtime.SpeechToTextSession
import com.modulai.core.runtime.TextToSpeechSession
import com.modulai.core.runtime.TranscribeOptions
import com.modulai.core.runtime.VoiceCloningSession
import com.modulai.core.runtime.VoiceKind
import com.modulai.core.runtime.Wav
import com.modulai.core.runtime.transcribeText
import com.modulai.engine.audio.ChatterboxTokenizer
import com.modulai.engine.audio.OnnxTtsEngine
import com.modulai.engine.audio.WhisperEngine
import com.modulai.engine.audio.WhisperNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + if (detail.isEmpty()) "" else "  [$detail]")
    if (!ok) failures++
}

/**
 * How many of [expected] words the recognizer heard, as "7/8". Words are looked for as substrings,
 * because whisper is free to write "river bank" as "riverbank".
 */
private fun heard(recognized: String, expected: List<String>): Pair<Int, String> {
    val text = recognized.lowercase().replace('ё', 'е')
    val hits = expected.count { it in text }
    return hits to "$hits/${expected.size}"
}

private fun <T> timed(label: String, block: () -> T): T {
    val start = System.nanoTime()
    return block().also { println("      $label took ${(System.nanoTime() - start) / 1_000_000} ms") }
}

fun main(args: Array<String>) {
    val models = File(args[0])
    val samples = File(args[1])
    val out = File(models.parentFile, "output").apply { mkdirs() }
    val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
    val config = LoadConfig(Backend.CPU, threads = threads)

    runBlocking {
        println("== Speech to text (whisper.cpp)")
        println("      ${WhisperNative.systemInfo().trim()}")
        val whisperEngine = WhisperEngine()
        val whisperModel = sttModel(File(models, "ggml-base-q5_1.bin"))
        check("engine accepts the model", whisperEngine.supports(whisperModel))
        val whisper = timed("load") { runBlocking { whisperEngine.load(whisperModel, config) } }
        check("model is multilingual", whisper.multilingual)

        val jfk = Wav.read(File(samples, "jfk.wav"))
        if (wanted("stt")) recognition(whisperEngine, whisper, jfk, models, out, config)

        val english = "The quick brown fox jumps over the lazy dog near the river bank."
        val englishWords = listOf("quick", "brown", "fox", "jumps", "over", "lazy", "dog", "river")
        val russian = "Сегодня хорошая погода, и мы идём гулять в парк."
        val russianWords = listOf("сегодня", "хорошая", "погода", "мы", "идем", "гулять", "парк")
        val ttsEngine = OnnxTtsEngine()
        if (wanted("tts")) synthesis(ttsEngine, whisper, models, out, config, english, englishWords, russian, russianWords)
        if (wanted("clone")) cloning(ttsEngine, whisper, jfk, models, out, config, english, englishWords, russian, russianWords)
        whisper.close()
    }

    println(if (failures == 0) "ALL AUDIO CHECKS PASSED" else "$failures CHECK(S) FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}

/** AUDIO_TEST_ONLY=stt,tts,clone limits the run to some sections; all run by default. */
private fun wanted(section: String) = System.getenv("AUDIO_TEST_ONLY")?.split(',')?.contains(section) ?: true

private suspend fun recognition(
    whisperEngine: WhisperEngine, whisper: SpeechToTextSession, jfk: Pcm, models: File, out: File, config: LoadConfig,
) = coroutineScope {
        val events = timed("transcribe 11 s") { runBlocking { whisper.transcribe(jfk, TranscribeOptions("en")).toList() } }
        val done = events.last() as SpeechEvent.Done
        println("      \"${done.text}\"")
        check("JFK sample is recognized", "ask not what your country can do for you" in done.text.lowercase())
        check("segments are reported", events.any { it is SpeechEvent.Segment })
        check("progress is reported", events.any { it is SpeechEvent.Progress && it.percent > 0 })
        check("finishes uncancelled", !done.cancelled)

        val auto = whisper.transcribe(jfk).toList().last() as SpeechEvent.Done
        check("language is detected", auto.language == "en", auto.language)

        // 44.1 kHz input goes through the resampler before recognition.
        val upsampled = whisper.transcribeText(jfk.resampled(44_100), TranscribeOptions("en"))
        check("other sample rates are accepted", "country" in upsampled.lowercase())

        val missing = runCatching { whisperEngine.load(sttModel(File(models, "no-such-model.bin")), config) }.exceptionOrNull()
        check("missing model gives a readable error", missing is SpeechException && "not found" in missing.message.orEmpty(), "${missing?.message}")
        val garbage = File(out, "garbage.bin").apply { writeText("this is not a model") }
        val broken = runCatching { whisperEngine.load(sttModel(garbage), config) }.exceptionOrNull()
        check("broken model gives a readable error", broken is SpeechException && broken.message.orEmpty().length > 20, "${broken?.message}")
        val badLanguage = runCatching { whisper.transcribe(jfk, TranscribeOptions("xx")).toList() }.exceptionOrNull()
        check("unknown language gives a readable error", badLanguage is SpeechException, "${badLanguage?.message}")

        // Cancel a job that has minutes of audio ahead of it, then time a short one queued behind
        // it: if the native loop really stops, the wait is about one short transcription.
        val shortStart = System.nanoTime()
        whisper.transcribeText(jfk, TranscribeOptions("en"))
        val shortMs = (System.nanoTime() - shortStart) / 1_000_000
        val long = Pcm.concat(List(16) { jfk })
        val longEvents = ArrayList<SpeechEvent>()
        val longJob = launch { whisper.transcribe(long, TranscribeOptions("en")).collect { longEvents += it } }
        delay(shortMs / 2)
        val cancelStart = System.nanoTime()
        longJob.cancelAndJoin()
        val afterCancel = whisper.transcribeText(jfk, TranscribeOptions("en"))
        val cancelMs = (System.nanoTime() - cancelStart) / 1_000_000
        check(
            "cancel stops a long transcription", longEvents.none { it is SpeechEvent.Done } && cancelMs < shortMs * 4,
            "176 s of audio dropped; next 11 s job done $cancelMs ms after cancel, alone it takes $shortMs ms",
        )
        check("session works again after cancel", "country" in afterCancel.lowercase())

}

private suspend fun synthesis(
    ttsEngine: OnnxTtsEngine, whisper: SpeechToTextSession, models: File, out: File, config: LoadConfig,
    english: String, englishWords: List<String>, russian: String, russianWords: List<String>,
) = coroutineScope {
        println("== Text to speech (Supertonic 3, ONNX Runtime)")
        val supertonicModel = ttsModel(File(models, "supertonic"))
        check("engine accepts the model", ttsEngine.supports(supertonicModel))
        val supertonic = timed("load") { runBlocking { ttsEngine.load(supertonicModel, config) } }
        println("      voices: ${supertonic.voices.joinToString { it.name }}; ${supertonic.languages.size} languages")
        check("Russian and English are offered", "ru" in supertonic.languages && "en" in supertonic.languages)
        roundTrip("English", supertonic, whisper, english, "en", englishWords, File(out, "supertonic-en.wav"), voice = 1)
        roundTrip("Russian", supertonic, whisper, russian, "ru", russianWords, File(out, "supertonic-ru.wav"), voice = 0)

        val normal = supertonic.synthesize(english, SpeechParams(language = "en"))
        val fast = supertonic.synthesize(english, SpeechParams(language = "en", speed = 1.5f))
        check("speed shortens the speech", fast.durationMs < normal.durationMs * 0.8, "${normal.durationMs} ms -> ${fast.durationMs} ms")

        val longText = List(12) { english }.joinToString(" ")
        val job = async { supertonic.synthesize(longText, SpeechParams(language = "en")) }
        delay(1500)
        val stopStart = System.nanoTime()
        job.cancel()
        val outcome = runCatching { job.await() }.exceptionOrNull()
        check("synthesis can be cancelled", outcome is CancellationException, "stopped in ${(System.nanoTime() - stopStart) / 1_000_000} ms")
        val unknown = runCatching { supertonic.synthesize("hello", SpeechParams(language = "xx")) }.exceptionOrNull()
        check("unsupported language gives a readable error", unknown is SpeechException, "${unknown?.message}")
        supertonic.close()

        val incomplete = File(out, "incomplete").apply { mkdirs() }
        File(models, "supertonic/vector_estimator.onnx").copyTo(File(incomplete, "vector_estimator.onnx"), overwrite = true)
        val partial = runCatching { ttsEngine.load(ttsModel(incomplete), config) }.exceptionOrNull()
        check("incomplete model gives a readable error", partial is SpeechException && "missing" in partial.message.orEmpty(), "${partial?.message}")

}

private suspend fun cloning(
    ttsEngine: OnnxTtsEngine, whisper: SpeechToTextSession, jfk: Pcm, models: File, out: File, config: LoadConfig,
    english: String, englishWords: List<String>, russian: String, russianWords: List<String>,
) {
        println("== Voice cloning (Chatterbox Multilingual, ONNX Runtime)")
        val chatterboxModel = ttsModel(File(models, "chatterbox"))
        checkTokenizer(File(models, "chatterbox/tokenizer.json"))
        check("engine accepts the model", ttsEngine.supports(chatterboxModel))
        val chatterbox = timed("load") { runBlocking { ttsEngine.load(chatterboxModel, config) } }
        check("model can clone voices", chatterbox is VoiceCloningSession)
        check("Russian and English are offered", "ru" in chatterbox.languages && "en" in chatterbox.languages)
        roundTrip("stock voice, English", chatterbox, whisper, english, "en", englishWords, File(out, "chatterbox-en.wav"), voice = 0)

        val cloner = chatterbox as VoiceCloningSession
        val profile = File(out, "jfk.voice").apply { delete() }
        val cloned = timed("clone from 10 s of speech") { runBlocking { cloner.cloneVoice(jfk, "JFK", profile) } }
        check("cloning stores a voice profile", cloned.kind == VoiceKind.CLONED && profile.length() > 10_000, "${profile.length()} bytes")
        roundTrip("cloned voice, English", chatterbox, whisper, english, "en", englishWords, File(out, "clone-en.wav"), cloned)
        roundTrip("cloned voice, Russian", chatterbox, whisper, russian, "ru", russianWords, File(out, "clone-ru.wav"), cloned)

        val tooShort = runCatching { cloner.cloneVoice(Pcm(FloatArray(4000), 16_000), "x", File(out, "x.voice")) }.exceptionOrNull()
        check("too short a sample gives a readable error", tooShort is SpeechException, "${tooShort?.message}")
        chatterbox.close()
}

/** Token ids produced by the Hugging Face `tokenizers` library for the same file and inputs. */
private val REFERENCE_TOKENS = listOf(
    Triple(
        "en", "The quick brown fox, jumps over the lazy dog! [laughter] It's 5 o'clock.",
        longArrayOf(
            6563, 255, 708, 42, 2, 194, 91, 24, 2, 243, 190, 2, 182, 37, 7, 2, 23, 231, 29, 32, 2, 253, 2, 42, 2, 25, 14,
            39, 38, 2, 134, 20, 3, 2, 607, 2, 60, 4, 32, 2, 268, 2, 28, 4, 16, 99, 197, 9, 0, 6561, 6561,
        ),
    ),
    Triple(
        "ru", "Сегодня хорошая погода, и мы идём гулять в парк. Ёжик — колючий?",
        longArrayOf(
            6563, 255, 716, 1138, 1127, 1125, 1135, 1126, 1134, 1152, 2, 1142, 1135, 1137, 1135, 1145, 1122, 1152, 2, 1136,
            1135, 1125, 1135, 1126, 1122, 7, 2, 1130, 2, 1133, 1148, 2, 1130, 1126, 1127, 762, 1133, 2, 1125, 1140, 1132,
            1152, 1139, 1149, 2, 1124, 2, 1136, 1122, 1137, 1131, 9, 2, 1127, 762, 1128, 1130, 1131, 2, 317, 2, 1131, 1135,
            1132, 1151, 1144, 1130, 1130, 771, 13, 0, 6561, 6561,
        ),
    ),
    Triple(
        "de", "Größe über alles: straße",
        longArrayOf(6563, 255, 636, 20, 89, 762, 392, 18, 2, 34, 762, 15, 44, 2, 108, 61, 11, 2, 63, 31, 14, 392, 18, 0, 6561, 6561),
    ),
)

private fun checkTokenizer(file: File) {
    val tokenizer = ChatterboxTokenizer(file.readText())
    for ((language, text, expected) in REFERENCE_TOKENS) {
        val ids = tokenizer.encode(text, language)
        check("tokenizer matches the reference for \"$language\"", ids.contentEquals(expected), if (ids.contentEquals(expected)) "" else ids.joinToString())
    }
}

/** Synthesizes [text], saves it, checks the sound is plausible and that whisper hears the words back. */
private suspend fun roundTrip(
    label: String, tts: TextToSpeechSession, stt: SpeechToTextSession, text: String, language: String,
    expected: List<String>, wav: File, voice: com.modulai.core.runtime.Voice,
) {
    val start = System.nanoTime()
    val pcm = tts.synthesize(text, SpeechParams(voice, language))
    val ms = (System.nanoTime() - start) / 1_000_000
    Wav.write(wav, pcm)
    val seconds = pcm.durationMs / 1000.0
    println("      $label: ${"%.1f".format(seconds)} s at ${pcm.sampleRate} Hz in $ms ms (x${"%.2f".format(ms / 1000.0 / seconds)} real time), rms ${"%.3f".format(pcm.rms)} -> ${wav.name}")
    check("$label: audio is not silent", pcm.rms > 0.01f, "rms ${pcm.rms}")
    check("$label: duration is plausible", seconds in 2.0..12.0, "$seconds s")
    val recognized = stt.transcribeText(Wav.read(wav), TranscribeOptions(language))
    println("      whisper heard: \"$recognized\"")
    val (hits, ratio) = heard(recognized, expected)
    check("$label: words survive the round trip", hits >= expected.size - 1, ratio)
}

private suspend fun roundTrip(
    label: String, tts: TextToSpeechSession, stt: SpeechToTextSession, text: String, language: String,
    expected: List<String>, wav: File, voice: Int,
) = roundTrip(label, tts, stt, text, language, expected, wav, tts.voices[voice])

private fun sttModel(file: File) =
    ModelDescriptor(file.nameWithoutExtension, file.path, ModelFormat.GGUF, Capability.SPEECH_TO_TEXT, file.length())

private fun ttsModel(dir: File) =
    ModelDescriptor(dir.name, dir.path, ModelFormat.ONNX, Capability.TEXT_TO_SPEECH, 0)
