package com.modulai.core.characters

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Reads the textual chunks (tEXt, zTXt, iTXt) of a PNG file; character cards live there. */
object PngText {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** A compressed chunk expands to at most this much, so a tiny file cannot exhaust memory. */
    private const val MAX_INFLATED = 16 shl 20

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /**
     * Keyword → text for every textual chunk up to IEND; the first chunk wins for a repeated keyword.
     * CRCs are not checked: tools that re-save cards get them wrong often enough, and the text is
     * validated as a card afterwards anyway.
     */
    fun read(bytes: ByteArray): Map<String, String> {
        if (!isPng(bytes)) throw bad("not a PNG file")
        val result = LinkedHashMap<String, String>()
        var pos = SIGNATURE.size
        while (true) {
            if (bytes.size - pos < 8) throw bad("the file ends before the IEND chunk")
            val length = uint32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            // Data plus the 4-byte CRC must fit into what is left of the file.
            if (length > bytes.size - start - 4) throw bad("chunk $type is longer than the file")
            val end = start + length.toInt()
            if (type == "IEND") return result
            if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
                val zero = indexOfZero(bytes, start, end)
                if (zero >= 0) {
                    val keyword = String(bytes, start, zero - start, Charsets.ISO_8859_1)
                    val value = when (type) {
                        "tEXt" -> String(bytes, zero + 1, end - zero - 1, Charsets.ISO_8859_1)
                        // One byte of compression method precedes the deflate stream.
                        "zTXt" -> if (zero + 2 <= end) String(inflate(bytes, zero + 2, end), Charsets.ISO_8859_1) else null
                        else -> international(bytes, zero + 1, end)
                    }
                    if (value != null) result.putIfAbsent(keyword, value)
                }
            }
            pos = end + 4
        }
    }

    /** iTXt after the keyword: compression flag, method, language tag, translated keyword, UTF-8 text. */
    private fun international(bytes: ByteArray, at: Int, end: Int): String? {
        if (end - at < 2) return null
        val compressed = bytes[at].toInt() == 1
        val language = indexOfZero(bytes, at + 2, end)
        if (language < 0) return null
        val translated = indexOfZero(bytes, language + 1, end)
        if (translated < 0) return null
        val from = translated + 1
        return if (compressed) String(inflate(bytes, from, end), Charsets.UTF_8) else String(bytes, from, end - from, Charsets.UTF_8)
    }

    private fun inflate(bytes: ByteArray, from: Int, end: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, from, end - from)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw bad("compressed text is cut short")
                out.write(buffer, 0, n)
                if (out.size() > MAX_INFLATED) throw bad("compressed text is too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw bad("compressed text is damaged")
        } finally {
            inflater.end()
        }
    }

    private fun indexOfZero(bytes: ByteArray, from: Int, end: Int): Int {
        for (i in from until end) if (bytes[i].toInt() == 0) return i
        return -1
    }

    private fun uint32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

    private fun bad(what: String) = CardFormatException(CardError.BAD_PNG, "Malformed PNG: $what")
}
