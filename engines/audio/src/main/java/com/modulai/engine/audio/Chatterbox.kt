package com.modulai.engine.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.SpeechException
import com.modulai.core.runtime.splitForSpeech
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * What the speech encoder extracts from a reference recording. Synthesis needs nothing else
 * from the speaker, so this is all a cloned voice consists of.
 */
internal class VoiceProfile(
    val condEmb: FloatArray, val condShape: LongArray,
    val promptTokens: LongArray,
    val speakerEmbedding: FloatArray, val speakerShape: LongArray,
    val promptFeatures: FloatArray, val featureShape: LongArray,
) {
    fun write(file: File) {
        val part = File(file.parentFile, file.name + ".part")
        DataOutputStream(part.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            writeFloats(out, condEmb, condShape)
            out.writeInt(promptTokens.size)
            promptTokens.forEach { out.writeInt(it.toInt()) }
            writeFloats(out, speakerEmbedding, speakerShape)
            writeFloats(out, promptFeatures, featureShape)
        }
        if (!part.renameTo(file)) throw IOException("Could not store ${file.name}")
    }

    companion object {
        private const val MAGIC = 0x4D56_4331 // "MVC1"

        fun read(file: File): VoiceProfile = DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) throw SpeechException("${file.name} is not a voice profile")
            val (cond, condShape) = readFloats(input)
            val tokens = LongArray(input.readInt()) { input.readInt().toLong() }
            val (speaker, speakerShape) = readFloats(input)
            val (features, featureShape) = readFloats(input)
            VoiceProfile(cond, condShape, tokens, speaker, speakerShape, features, featureShape)
        }

        private fun writeFloats(out: DataOutputStream, data: FloatArray, shape: LongArray) {
            out.writeInt(shape.size)
            shape.forEach { out.writeInt(it.toInt()) }
            data.forEach { out.writeFloat(it) }
        }

        private fun readFloats(input: DataInputStream): Pair<FloatArray, LongArray> {
            val shape = LongArray(input.readInt()) { input.readInt().toLong() }
            val data = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt()) { input.readFloat() }
            return data to shape
        }
    }
}

/**
 * Chatterbox Multilingual: a 0.5B language model turns text plus a speaker prompt into speech
 * tokens, and a flow-matching decoder with a vocoder turns those into 24 kHz sound. The speaker
 * prompt comes from a few seconds of anyone's speech, which is what makes voice cloning work.
 */
internal class Chatterbox(private val dir: File, private val threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val tokenizer = ChatterboxTokenizer(required("tokenizer.json").readText())
    private val embedTokens = open("embed_tokens.onnx")
    private val languageModel = open(LANGUAGE_MODELS.firstOrNull { File(dir, it).isFile } ?: LANGUAGE_MODELS[0])
    private val decoder = open("conditional_decoder.onnx")

    val sampleRate = SAMPLE_RATE

    fun supports(language: String) = language in LANGUAGES && tokenizer.supports(language)

    /**
     * Runs the speech encoder over [reference]. The encoder is as large as the rest of the model
     * together and is only needed here, so it is loaded for the call and released again.
     */
    fun encodeVoice(reference: Pcm): VoiceProfile {
        val audio = reference.resampled(SAMPLE_RATE).samples.let { it.copyOf(minOf(it.size, REFERENCE_SECONDS * SAMPLE_RATE)) }
        if (audio.size < SAMPLE_RATE) throw SpeechException("The voice sample is too short: at least a second of speech is needed")
        open("speech_encoder.onnx").use { encoder ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { input ->
                encoder.run(mapOf("audio_values" to input)).use { out ->
                    val tokens = (out.get(1) as OnnxTensor).longBuffer
                    return VoiceProfile(
                        out.floats(0), out.shape(0),
                        LongArray(tokens.remaining()).also { tokens.get(it) },
                        out.floats(2), out.shape(2),
                        out.floats(3), out.shape(3),
                    )
                }
            }
        }
    }

    fun synthesize(text: String, language: String, voice: VoiceProfile, checkCancelled: () -> Unit): Pcm {
        if (!supports(language)) throw SpeechException("Chatterbox does not speak \"$language\"")
        val parts = splitForSpeech(text, MAX_CHARS).map { chunk ->
            checkCancelled()
            val tokens = generate(normalize(chunk), language, voice, checkCancelled)
            checkCancelled()
            Pcm(decode(tokens, voice), SAMPLE_RATE)
        }
        if (parts.isEmpty()) throw SpeechException("There is no text to speak")
        // The decoder starts on the very first sample of speech; players swallow such an onset.
        return Pcm(FloatArray(SAMPLE_RATE * LEAD_IN_MS / 1000) + Pcm.concat(parts, gapMs = 200).samples, SAMPLE_RATE)
    }

    /** Greedy decoding of speech tokens with a key/value cache, as in the model's reference script. */
    private fun generate(text: String, language: String, voice: VoiceProfile, checkCancelled: () -> Unit): LongArray {
        var ids = tokenizer.encode(text, language)
        var positions = LongArray(ids.size) { if (ids[it] >= ChatterboxTokenizer.START_SPEECH) 0 else it - 1L }
        // Roughly 25 speech tokens make a second; nobody speaks slower than this allows.
        val limit = (100 + text.length * 6).coerceAtMost(MAX_TOKENS)

        val generated = ArrayList<Long>(limit)
        val seen = HashSet<Int>().apply { add(ChatterboxTokenizer.START_SPEECH.toInt()) }
        var sequenceLength = 0L
        var previous: OrtSession.Result? = null
        val owned = ArrayList<OnnxTensor>()
        try {
            var cache: Map<String, OnnxTensorLike> = languageModel.inputInfo
                .filterKeys { it.startsWith(PAST_PREFIX) }
                .mapValues { (_, node) ->
                    // [batch, heads, sequence, head size] with an empty sequence.
                    val shape = (node.info as TensorInfo).shape.also { it[0] = 1; it[2] = 0 }
                    OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape).also { owned += it }
                }
            for (step in 0 until limit) {
                checkCancelled()
                var embeds = embed(ids, positions)
                var count = ids.size.toLong()
                if (step == 0) {
                    embeds = voice.condEmb + embeds
                    count += voice.condShape[1]
                }
                sequenceLength += count
                val hidden = embeds.size / count
                val next = OnnxTensor.createTensor(env, FloatBuffer.wrap(embeds), longArrayOf(1, count, hidden)).use { input ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(sequenceLength.toInt()) { 1 }), longArrayOf(1, sequenceLength)).use { mask ->
                        val result = languageModel.run(cache + mapOf("inputs_embeds" to input, "attention_mask" to mask))
                        previous?.close()
                        previous = result
                        cache = HashMap<String, OnnxTensorLike>().also { map ->
                            for ((name, value) in result) {
                                if (name.startsWith(PRESENT_PREFIX)) map[PAST_PREFIX + name.removePrefix(PRESENT_PREFIX)] = value as OnnxTensor
                            }
                        }
                        pickToken(result.get("logits").get() as OnnxTensor, seen)
                    }
                }
                if (next == ChatterboxTokenizer.STOP_SPEECH) break
                generated += next
                seen += next.toInt()
                ids = longArrayOf(next)
                positions = longArrayOf(step + 1L)
            }
        } finally {
            previous?.close()
            owned.forEach { it.close() }
        }
        return generated.toLongArray()
    }

    private fun embed(ids: LongArray, positions: LongArray): FloatArray {
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(positions), shape).use { positionIds ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(EXAGGERATION)), longArrayOf(1)).use { exaggeration ->
                    return embedTokens.run(
                        mapOf("input_ids" to inputIds, "position_ids" to positionIds, "exaggeration" to exaggeration),
                    ).use { it.floats(0) }
                }
            }
        }
    }

    /** Argmax over the last position, after pushing down every token that was already produced. */
    private fun pickToken(logits: OnnxTensor, seen: Set<Int>): Long {
        val shape = logits.info.shape
        val vocab = shape[2].toInt()
        val buffer = logits.floatBuffer
        val offset = (shape[1].toInt() - 1) * vocab
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            var score = buffer.get(offset + i)
            if (i in seen) score = if (score < 0) score * REPETITION_PENALTY else score / REPETITION_PENALTY
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best.toLong()
    }

    private fun decode(tokens: LongArray, voice: VoiceProfile): FloatArray {
        if (tokens.isEmpty()) return FloatArray(0)
        val all = voice.promptTokens + tokens
        OnnxTensor.createTensor(env, LongBuffer.wrap(all), longArrayOf(1, all.size.toLong())).use { speechTokens ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.speakerEmbedding), voice.speakerShape).use { embedding ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.promptFeatures), voice.featureShape).use { features ->
                    return decoder.run(
                        mapOf("speech_tokens" to speechTokens, "speaker_embeddings" to embedding, "speaker_features" to features),
                    ).use { it.floats(0) }
                }
            }
        }
    }

    /** Punctuation the model reads badly is replaced with what it was trained on. */
    private fun normalize(raw: String): String {
        var text = raw.replace(WHITESPACE, " ").trim()
        for ((from, to) in REPLACEMENTS) text = text.replace(from, to)
        if (text.isNotEmpty() && text.last() !in SENTENCE_ENDINGS) text += "."
        return text
    }

    private fun required(name: String) =
        File(dir, name).also { if (!it.isFile) throw SpeechException("${dir.name} is incomplete: $name is missing") }

    private fun open(name: String): OrtSession = env.createSession(required(name).path, options)

    override fun close() {
        embedTokens.close()
        languageModel.close()
        decoder.close()
        options.close()
    }

    companion object {
        const val REQUIRED_FILE = "conditional_decoder.onnx"
        const val SAMPLE_RATE = 24_000

        /** The encoder conditions on the first ten seconds of the reference and ignores the rest. */
        const val REFERENCE_SECONDS = 10

        /**
         * Languages of the model, minus Chinese, Japanese and Hebrew: those need a word segmenter,
         * a kanji reader and a diacritizer in front of the tokenizer, which are not part of the app.
         */
        val LANGUAGES = setOf(
            "ar", "da", "de", "el", "en", "es", "fi", "fr", "hi", "it", "ko", "ms", "nl", "no", "pl", "pt",
            "ru", "sv", "sw", "tr",
        )

        // 4-bit weights first: a sixth of the download and of the memory of the full-precision file.
        private val LANGUAGE_MODELS = listOf("language_model_q4.onnx", "language_model.onnx")
        private const val PAST_PREFIX = "past_key_values"
        private const val PRESENT_PREFIX = "present"
        private const val EXAGGERATION = 0.5f
        private const val REPETITION_PENALTY = 1.2f
        private const val MAX_TOKENS = 1000
        private const val MAX_CHARS = 250
        private const val LEAD_IN_MS = 200
        private val WHITESPACE = Regex("""\s+""")
        private val REPLACEMENTS = listOf(
            "..." to ", ", "…" to ", ", ":" to ",", " - " to ", ", ";" to ", ", "—" to "-", "–" to "-",
            " ," to ",", "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'",
        )
        private const val SENTENCE_ENDINGS = ".!?-,、，。？！"
    }
}

internal fun OrtSession.Result.shape(index: Int): LongArray = (get(index) as OnnxTensor).info.shape
