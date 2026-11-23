package com.modulai.core.runtime

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Mono sound, samples in -1..1. */
class Pcm(val samples: FloatArray, val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
    }

    val durationMs: Long get() = samples.size * 1000L / sampleRate

    /** Root mean square level; silence is 0, a full-scale sine is about 0.7. */
    val rms: Float
        get() {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) sum += s * s
            return sqrt(sum / samples.size).toFloat()
        }

    fun toShortArray(): ShortArray = ShortArray(samples.size) {
        (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
    }

    /** Band-limited conversion to [rate]; returns this object when the rate already matches. */
    fun resampled(rate: Int): Pcm {
        if (rate == sampleRate) return this
        if (samples.isEmpty()) return Pcm(samples, rate)
        val ratio = rate.toDouble() / sampleRate
        // Below the lower of the two Nyquist limits, so that downsampling does not alias.
        val cutoff = min(1.0, ratio)
        val halfWidth = ceil(RESAMPLE_ZERO_CROSSINGS / cutoff).toInt()
        val out = FloatArray((samples.size * ratio).toLong().toInt())
        for (i in out.indices) {
            val center = i / ratio
            val first = center.toInt() - halfWidth + 1
            var acc = 0.0
            var weight = 0.0
            for (j in first..first + 2 * halfWidth - 1) {
                if (j < 0 || j >= samples.size) continue
                val x = (j - center) * cutoff
                val window = 0.5 + 0.5 * cos(PI * x / RESAMPLE_ZERO_CROSSINGS)
                val w = (if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)) * window
                acc += samples[j] * w
                weight += w
            }
            out[i] = if (weight != 0.0) (acc / weight).toFloat() else 0f
        }
        return Pcm(out, rate)
    }

    companion object {
        private const val RESAMPLE_ZERO_CROSSINGS = 8

        fun fromShorts(shorts: ShortArray, sampleRate: Int, channels: Int = 1): Pcm {
            val frames = shorts.size / channels
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += shorts[i * channels + c]
                out[i] = sum / (channels * 32768f)
            }
            return Pcm(out, sampleRate)
        }

        fun concat(parts: List<Pcm>, gapMs: Int = 0): Pcm {
            require(parts.isNotEmpty()) { "Nothing to join" }
            val rate = parts[0].sampleRate
            val gap = rate * gapMs / 1000
            val out = FloatArray(parts.sumOf { it.samples.size } + gap * (parts.size - 1))
            var at = 0
            for (p in parts) {
                require(p.sampleRate == rate) { "Parts differ in sample rate" }
                p.samples.copyInto(out, at)
                at += p.samples.size + gap
            }
            return Pcm(out, rate)
        }
    }
}

/** RIFF/WAVE files: written as 16-bit mono, read from 16-bit integer or 32-bit float PCM. */
object Wav {
    fun encode(pcm: Pcm): ByteArray {
        val data = pcm.toShortArray()
        val buf = ByteBuffer.allocate(44 + data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data.size * 2).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buf.putInt(pcm.sampleRate).putInt(pcm.sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(data.size * 2)
        buf.asShortBuffer().put(data)
        return buf.array()
    }

    fun decode(bytes: ByteArray): Pcm {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || tag(buf, 0) != "RIFF" || tag(buf, 8) != "WAVE") {
            throw IllegalArgumentException("Not a WAV file")
        }
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = tag(buf, at)
            val size = buf.getInt(at + 4)
            val body = at + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = buf.getShort(body).toInt() and 0xFFFF
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
                // WAVE_FORMAT_EXTENSIBLE keeps the real format in the first two bytes of its GUID.
                if (format == 0xFFFE && size >= 26) format = buf.getShort(body + 24).toInt() and 0xFFFF
            } else if (id == "data") {
                if (channels <= 0 || rate <= 0) throw IllegalArgumentException("WAV has no format header")
                // Recorders that stream to a pipe leave the size as 0 or -1.
                val length = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                return decodeSamples(buf, body, length, format, channels, rate, bits)
            }
            at = body + size + (size and 1)
        }
        throw IllegalArgumentException("WAV has no audio data")
    }

    fun write(file: File, pcm: Pcm) = file.writeBytes(encode(pcm))

    fun read(file: File): Pcm = decode(file.readBytes())

    private fun decodeSamples(
        buf: ByteBuffer, start: Int, length: Int, format: Int, channels: Int, rate: Int, bits: Int,
    ): Pcm {
        val bytesPerSample = bits / 8
        val read: (Int) -> Float = when {
            format == 1 && bits == 16 -> { i -> buf.getShort(i) / 32768f }
            format == 1 && bits == 24 -> { i ->
                ((buf.get(i + 2).toInt() shl 16) or ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                    (buf.get(i).toInt() and 0xFF)) / 8388608f
            }
            format == 1 && bits == 32 -> { i -> buf.getInt(i) / 2147483648f }
            format == 3 && bits == 32 -> { i -> buf.getFloat(i) }
            else -> throw IllegalArgumentException("Unsupported WAV encoding (format $format, $bits bit)")
        }
        val frames = length / (bytesPerSample * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += read(start + (f * channels + c) * bytesPerSample)
            out[f] = sum / channels
        }
        return Pcm(out, rate)
    }

    private fun tag(buf: ByteBuffer, at: Int) = String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)
}

data class SpeechSegment(val startMs: Long, val endMs: Long, val text: String)

sealed interface SpeechEvent {
    data class Progress(val percent: Int) : SpeechEvent
    data class Segment(val segment: SpeechSegment) : SpeechEvent

    /** Always the last event. [language] is the code the recognizer worked in. */
    data class Done(val text: String, val language: String, val cancelled: Boolean) : SpeechEvent
}

data class TranscribeOptions(
    /** A two-letter code such as "ru" or "en", or [AUTO] to let the model decide. */
    val language: String = AUTO,
    /** Output English whatever the spoken language is. */
    val translate: Boolean = false,
    val threads: Int = 4,
) {
    companion object {
        const val AUTO = "auto"
    }
}

class SpeechException(message: String) : Exception(message)

interface SpeechToTextSession : Session {
    /** False for English-only models, which ignore the language option. */
    val multilingual: Boolean

    /**
     * Recognizes speech in [pcm] (any sample rate). Cancelling the collector stops the work;
     * failures surface as [SpeechException].
     */
    fun transcribe(pcm: Pcm, options: TranscribeOptions = TranscribeOptions()): Flow<SpeechEvent>
}

/** Collects [SpeechToTextSession.transcribe] down to the final text. */
suspend fun SpeechToTextSession.transcribeText(pcm: Pcm, options: TranscribeOptions = TranscribeOptions()): String {
    var text = ""
    transcribe(pcm, options).collect { if (it is SpeechEvent.Done) text = it.text }
    return text
}

enum class VoiceKind {
    /** Ships with the model. */
    PRESET,

    /** Made from a reference recording; [Voice.path] holds its profile. */
    CLONED,

    /** Provided by the operating system's speech service. */
    SYSTEM,
}

data class Voice(
    val id: String,
    val name: String,
    val kind: VoiceKind,
    /** Language codes the voice is meant for; empty means any language its engine speaks. */
    val languages: Set<String> = emptySet(),
    val path: String? = null,
)

data class SpeechParams(
    /** Null picks the session's first voice. */
    val voice: Voice? = null,
    val language: String = "en",
    val speed: Float = 1f,
)

interface TextToSpeechSession : Session {
    /** Voices built into the model. */
    val voices: List<Voice>

    /** Language codes the model can speak; empty when that depends on the voice alone. */
    val languages: Set<String>

    /** False when [SpeechParams.speed] has no effect on this model. */
    val adjustableSpeed: Boolean

    /** Renders [text]. Cancelling the caller stops the work; failures surface as [SpeechException]. */
    suspend fun synthesize(text: String, params: SpeechParams = SpeechParams()): Pcm
}

/** A speech model that can imitate a voice from a short recording. */
interface VoiceCloningSession : TextToSpeechSession {
    /** Seconds of reference speech the model makes use of; more is cut off. */
    val referenceSeconds: Int

    /**
     * Analyses [reference] and stores the voice profile in [target]. The returned voice can be
     * passed to [synthesize] of any session of the same model.
     */
    suspend fun cloneVoice(reference: Pcm, name: String, target: File): Voice
}

/** Splits text into pieces of at most [maxChars], breaking at sentence ends where it can. */
fun splitForSpeech(text: String, maxChars: Int): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotBlank()) chunks += current.toString().trim()
        current.setLength(0)
    }
    fun add(piece: String, joiner: String) {
        if (current.isNotEmpty() && current.length + joiner.length + piece.length > maxChars) flush()
        if (current.isNotEmpty()) current.append(joiner)
        current.append(piece)
    }
    for (sentence in text.trim().split(SENTENCE_END)) {
        val s = sentence.trim()
        if (s.isEmpty()) continue
        if (s.length <= maxChars) {
            add(s, " ")
            continue
        }
        flush()
        for (word in s.split(WHITESPACE)) {
            // A single word longer than the limit is cut rather than dropped.
            word.chunked(maxChars).forEach { add(it, " ") }
        }
        flush()
    }
    flush()
    return chunks
}

private val SENTENCE_END = Regex("""(?<=[.!?…。])\s+|\n+""")
private val WHITESPACE = Regex("""\s+""")
