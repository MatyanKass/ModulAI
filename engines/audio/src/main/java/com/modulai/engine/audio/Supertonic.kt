package com.modulai.engine.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.SpeechException
import com.modulai.core.runtime.splitForSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random

/**
 * Supertonic 3 text-to-speech: four ONNX graphs (duration predictor, text encoder, flow-matching
 * vector estimator, vocoder) fed with plain Unicode code points, so no phonemizer is involved.
 * A voice is a pair of style tensors stored as JSON next to the model.
 */
internal class Supertonic(private val dir: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val durationPredictor = open("duration_predictor.onnx")
    private val textEncoder = open("text_encoder.onnx")
    private val vectorEstimator = open("vector_estimator.onnx")
    private val vocoder = open("vocoder.onnx")

    private val indexer: IntArray
    private val chunkSize: Int
    private val latentDim: Int
    val sampleRate: Int

    private val styles = HashMap<String, Style>()

    init {
        val config = JSONObject(required("tts.json").readText())
        val ae = config.getJSONObject("ae")
        val ttl = config.getJSONObject("ttl")
        sampleRate = ae.getInt("sample_rate")
        val compress = ttl.getInt("chunk_compress_factor")
        chunkSize = ae.getInt("base_chunk_size") * compress
        latentDim = ttl.getInt("latent_dim") * compress
        val table = JSONArray(required("unicode_indexer.json").readText())
        indexer = IntArray(table.length()) { table.getInt(it) }
        if (voiceIds(dir).isEmpty()) throw SpeechException("No voice style files next to the model in ${dir.name}")
    }

    /**
     * [checkCancelled] is called between inference steps and is expected to throw to stop the work.
     */
    fun synthesize(
        text: String, language: String, voiceId: String, speed: Float, checkCancelled: () -> Unit,
    ): Pcm {
        if (language !in LANGUAGES) throw SpeechException("Supertonic does not speak \"$language\"")
        val style = styles.getOrPut(voiceId) { loadStyle(voiceId) }
        // Korean and Japanese pack more speech into a character.
        val limit = if (language == "ko" || language == "ja") 120 else 300
        val parts = splitForSpeech(text, limit).map {
            checkCancelled()
            Pcm(infer(it, language, style, speed, checkCancelled), sampleRate)
        }
        if (parts.isEmpty()) throw SpeechException("There is no text to speak")
        return Pcm.concat(parts, gapMs = 300)
    }

    private fun infer(text: String, language: String, style: Style, speed: Float, checkCancelled: () -> Unit): FloatArray {
        val ids = encode(text, language)
        val n = ids.size
        val textMask = FloatArray(n) { 1f }
        tensor(ids, 1, n).use { textIds ->
            tensor(textMask, 1, 1, n).use { mask ->
                tensor(style.ttl, *style.ttlShape).use { styleTtl ->
                    val seconds = tensor(style.dp, *style.dpShape).use { styleDp ->
                        durationPredictor.run(mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to mask))
                            .use { it.floats(0)[0] }
                    } / speed
                    val wavLength = (seconds * sampleRate).toInt()
                    val frames = (wavLength + chunkSize - 1) / chunkSize
                    if (frames <= 0) return FloatArray(0)

                    val random = Random()
                    var latent = FloatArray(latentDim * frames) { random.nextGaussian().toFloat() }
                    val latentMask = FloatArray(frames) { 1f }

                    textEncoder.run(mapOf("text_ids" to textIds, "style_ttl" to styleTtl, "text_mask" to mask)).use { enc ->
                        val textEmb = enc.get(0) as OnnxTensor
                        tensor(floatArrayOf(STEPS.toFloat()), 1).use { totalStep ->
                            tensor(latentMask, 1, 1, frames).use { lm ->
                                for (step in 0 until STEPS) {
                                    checkCancelled()
                                    latent = tensor(floatArrayOf(step.toFloat()), 1).use { current ->
                                        tensor(latent, 1, latentDim, frames).use { noisy ->
                                            vectorEstimator.run(
                                                mapOf(
                                                    "noisy_latent" to noisy, "text_emb" to textEmb,
                                                    "style_ttl" to styleTtl, "latent_mask" to lm,
                                                    "text_mask" to mask, "current_step" to current,
                                                    "total_step" to totalStep,
                                                ),
                                            ).use { it.floats(0) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    checkCancelled()
                    val wav = tensor(latent, 1, latentDim, frames).use { l ->
                        vocoder.run(mapOf("latent" to l)).use { it.floats(0) }
                    }
                    return wav.copyOf(minOf(wav.size, wavLength))
                }
            }
        }
    }

    /** Text clean-up the model was trained with, then one id per code point. */
    private fun encode(raw: String, language: String): LongArray {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        text = buildString {
            text.codePoints().forEach { if (!isEmoji(it)) appendCodePoint(it) }
        }
        for ((from, to) in REPLACEMENTS) text = text.replace(from, to)
        text = text.replace(STRAY_SYMBOLS, "")
        text = text.replace(SPACE_BEFORE_PUNCTUATION, "$1")
        text = text.replace(WHITESPACE, " ").trim()
        if (text.isNotEmpty() && text.last() !in SENTENCE_ENDINGS) text += "."
        text = "<$language>$text</$language>"

        val ids = ArrayList<Long>(text.length)
        text.codePoints().forEach { cp ->
            val id = if (cp < indexer.size) indexer[cp] else -1
            // Characters the model has never seen are skipped instead of failing the whole text.
            if (id >= 0) ids += id.toLong()
        }
        return ids.toLongArray()
    }

    private fun isEmoji(cp: Int) = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF

    private fun loadStyle(voiceId: String): Style {
        val file = File(dir, "$voiceId.json")
        if (!file.isFile) throw SpeechException("Voice $voiceId is not installed")
        val json = JSONObject(file.readText())
        val (ttl, ttlShape) = readTensor(json.getJSONObject("style_ttl"))
        val (dp, dpShape) = readTensor(json.getJSONObject("style_dp"))
        return Style(ttl, ttlShape, dp, dpShape)
    }

    private fun readTensor(node: JSONObject): Pair<FloatArray, LongArray> {
        val dims = node.getJSONArray("dims")
        val shape = LongArray(dims.length()) { dims.getLong(it) }
        val out = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt())
        var at = 0
        fun walk(a: JSONArray) {
            for (i in 0 until a.length()) {
                when (val v = a.get(i)) {
                    is JSONArray -> walk(v)
                    else -> out[at++] = (v as Number).toFloat()
                }
            }
        }
        walk(node.getJSONArray("data"))
        return out to shape
    }

    private fun required(name: String) =
        File(dir, name).also { if (!it.isFile) throw SpeechException("${dir.name} is incomplete: $name is missing") }

    private fun open(name: String): OrtSession = env.createSession(required(name).path, options)

    private fun tensor(data: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    private fun tensor(data: LongArray, vararg shape: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
    private fun tensor(data: FloatArray, vararg shape: Int) = tensor(data, *LongArray(shape.size) { shape[it].toLong() })
    private fun tensor(data: LongArray, vararg shape: Int) = tensor(data, *LongArray(shape.size) { shape[it].toLong() })

    override fun close() {
        durationPredictor.close()
        textEncoder.close()
        vectorEstimator.close()
        vocoder.close()
        options.close()
    }

    private class Style(val ttl: FloatArray, val ttlShape: LongArray, val dp: FloatArray, val dpShape: LongArray)

    companion object {
        const val REQUIRED_FILE = "vector_estimator.onnx"

        /** Flow-matching steps; the reference implementation's default trade-off of speed and quality. */
        private const val STEPS = 5

        val LANGUAGES = setOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
            "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
        )

        private val CONFIG_FILES = setOf("tts.json", "unicode_indexer.json")

        /** Names of the voice style files found next to the model, without the extension. */
        fun voiceIds(dir: File): List<String> =
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name !in CONFIG_FILES }
                .orEmpty().map { it.nameWithoutExtension }.sorted()
        private val REPLACEMENTS = listOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ", "“" to "\"", "”" to "\"",
            "‘" to "'", "’" to "'", "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ",
            "/" to " ", "#" to " ", "→" to " ", "←" to " ", "@" to " at ",
        )
        private val STRAY_SYMBOLS = Regex("""[♥☆♡©\\]""")
        private val SPACE_BEFORE_PUNCTUATION = Regex(""" ([,.!?;:'])""")
        private val WHITESPACE = Regex("""\s+""")
        private const val SENTENCE_ENDINGS = ".!?;:,'\")]}…。」』】〉》›»"
    }
}

/** Copies output [index] out of the native buffer. */
internal fun OrtSession.Result.floats(index: Int): FloatArray {
    val buffer = (get(index) as OnnxTensor).floatBuffer
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}
