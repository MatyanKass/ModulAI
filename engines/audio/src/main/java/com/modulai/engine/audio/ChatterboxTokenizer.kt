package com.modulai.engine.audio

import org.json.JSONObject
import java.text.Normalizer

/**
 * The text tokenizer of Chatterbox Multilingual, read from its Hugging Face `tokenizer.json`:
 * spaces become a `[SPACE]` token, bracketed markers such as `[ru]` are whole tokens, and the
 * rest is byte-pair encoded per word over a small grapheme vocabulary.
 */
internal class ChatterboxTokenizer(json: String) {
    private val vocab = HashMap<String, Int>()
    private val mergeRank = HashMap<Pair<String, String>, Int>()
    private val markers: List<Pair<String, Int>>
    private val unknown: Int

    init {
        val root = JSONObject(json)
        val model = root.getJSONObject("model")
        val v = model.getJSONObject("vocab")
        for (key in v.keys()) vocab[key] = v.getInt(key)
        val merges = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            // Older files store a merge as "a b", newer ones as ["a", "b"].
            val pair = when (val m = merges.get(i)) {
                is String -> m.substringBefore(' ') to m.substringAfter(' ')
                else -> merges.getJSONArray(i).let { it.getString(0) to it.getString(1) }
            }
            mergeRank[pair] = i
        }
        unknown = vocab[model.optString("unk_token", "[UNK]")] ?: 1
        val added = root.getJSONArray("added_tokens")
        markers = (0 until added.length())
            .map { added.getJSONObject(it).let { t -> t.getString("content") to t.getInt("id") } }
            .sortedByDescending { it.first.length }
    }

    fun supports(language: String) = markers.any { it.first == "[$language]" }

    /** Token ids for [text] spoken in [language], wrapped the way the language model expects. */
    fun encode(text: String, language: String): LongArray {
        val prepared = "[$language]" + Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(" ", "[SPACE]")
        val ids = ArrayList<Long>(prepared.length + 5)
        ids += EXAGGERATION
        ids += START_TEXT
        val plain = StringBuilder()
        var i = 0
        while (i < prepared.length) {
            val marker = if (prepared[i] == '[') markers.firstOrNull { prepared.startsWith(it.first, i) } else null
            if (marker == null) {
                plain.append(prepared[i++])
                continue
            }
            encodePlain(plain, ids)
            ids += marker.second.toLong()
            i += marker.first.length
        }
        encodePlain(plain, ids)
        ids += STOP_TEXT
        ids += START_SPEECH
        ids += START_SPEECH
        return ids.toLongArray()
    }

    private fun encodePlain(plain: StringBuilder, out: MutableList<Long>) {
        for (word in WORD.findAll(plain)) {
            for (piece in bpe(word.value)) out += (vocab[piece] ?: unknown).toLong()
        }
        plain.setLength(0)
    }

    private fun bpe(word: String): List<String> {
        val parts = ArrayList<String>(word.length)
        word.codePoints().forEach { parts += String(Character.toChars(it)) }
        while (parts.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val rank = mergeRank[parts[i] to parts[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = i
                }
            }
            if (best < 0) break
            parts[best] = parts[best] + parts[best + 1]
            parts.removeAt(best + 1)
        }
        return parts
    }

    companion object {
        const val START_SPEECH = 6561L
        const val STOP_SPEECH = 6562L
        private const val EXAGGERATION = 6563L
        private const val START_TEXT = 255L
        private const val STOP_TEXT = 0L

        // The tokenizer's "Whitespace" pre-tokenizer: runs of word characters or runs of anything else.
        private val WORD = Regex("""[\p{L}\p{M}\p{N}_]+|[^\p{L}\p{M}\p{N}_\s]+""")
    }
}
