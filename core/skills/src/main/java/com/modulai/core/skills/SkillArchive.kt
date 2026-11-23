package com.modulai.core.skills

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A skill before it is installed: SKILL.md plus the text files that came with it. */
class SkillBundle(
    val skillMd: String,
    /** Relative path to content; never contains SKILL.md itself. */
    val files: Map<String, ByteArray>,
    /** Used as the skill name when SKILL.md does not give one. */
    val fallbackName: String,
)

object SkillArchive {
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_EXTRA_FILES = 24

    /** Everything kept in memory while scanning; an archive past these is not a skill. */
    private const val MAX_KEPT_FILES = 200
    private const val MAX_KEPT_BYTES = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 5000
    private const val MAX_SCANNED_BYTES = 64L * 1024 * 1024

    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4

    /**
     * Reads a ZIP with SKILL.md at its root or inside its only top-level folder (the layout of
     * GitHub archives). Binary and oversized files are left out; unsafe paths fail the whole archive.
     */
    fun read(stream: InputStream, fallbackName: String = "skill"): SkillBundle {
        val kept = LinkedHashMap<String, ByteArray>()
        var keptBytes = 0
        var entries = 0
        var scanned = 0L
        val buffer = ByteArray(8192)

        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw SkillFormatException("The archive has too many files")
                val path = safePath(entry.name)
                val out = ByteArrayOutputStream()
                var size = 0L
                // Sizes come from the bytes actually inflated: the declared size can lie.
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    size += n
                    scanned += n
                    if (scanned > MAX_SCANNED_BYTES) throw SkillFormatException("The archive is too large")
                    if (size <= MAX_FILE_BYTES) out.write(buffer, 0, n)
                }
                if (entry.isDirectory || path == null) continue
                val isManifest = path.substringAfterLast('/').equals(SkillParser.FILE_NAME, ignoreCase = true)
                if (size > MAX_FILE_BYTES) {
                    if (isManifest) throw SkillFormatException("SKILL.md is too large")
                    continue
                }
                val bytes = out.toByteArray()
                if (bytes.contains(0)) continue
                keptBytes += bytes.size
                if (kept.size >= MAX_KEPT_FILES || keptBytes > MAX_KEPT_BYTES) {
                    throw SkillFormatException("The archive is too large for a skill")
                }
                kept[path] = bytes
            }
        }

        val tops = kept.keys.map { it.substringBefore('/', "") }.toSet()
        val prefix = when {
            kept.keys.any { it.equals(SkillParser.FILE_NAME, ignoreCase = true) } -> ""
            tops.size == 1 && tops.first().isNotEmpty() -> tops.first() + "/"
            else -> throw SkillFormatException("No SKILL.md at the top of the archive")
        }
        val manifestPath = kept.keys.firstOrNull { it.equals(prefix + SkillParser.FILE_NAME, ignoreCase = true) }
            ?: throw SkillFormatException("No SKILL.md at the top of the archive")
        val extras = kept.filterKeys { it != manifestPath && it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
            .entries.sortedBy { it.key }.take(MAX_EXTRA_FILES)
            .associate { it.key to it.value }
        return SkillBundle(
            skillMd = String(kept.getValue(manifestPath), Charsets.UTF_8),
            files = extras,
            fallbackName = prefix.trimEnd('/').ifEmpty { fallbackName },
        )
    }

    /**
     * The entry name as a clean relative path, or null for entries that are simply ignored
     * (hidden files, macOS metadata). Paths that try to leave the folder are an error.
     */
    internal fun safePath(name: String): String? {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw SkillFormatException("Unsafe path in the archive: $name")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw SkillFormatException("Unsafe path in the archive: $name")
        if (segments.isEmpty() || segments.any { it.startsWith(".") } || segments.first() == "__MACOSX") return null
        return segments.joinToString("/")
    }
}

/** Reads up to [max] bytes. InputStream.readNBytes would do, but it needs Android 13. */
internal fun InputStream.readAtMost(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
