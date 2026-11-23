package com.modulai.core.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Where a link the user pasted leads. */
sealed interface SkillLink {
    /** A SKILL.md or a ZIP archive; which one is decided by the downloaded content. */
    data class Download(val url: String, val fallbackName: String) : SkillLink

    /** A folder of a GitHub repository with SKILL.md in it. [path] is empty for the repository root. */
    data class GithubFolder(val owner: String, val repo: String, val ref: String, val path: String) : SkillLink {
        val skillUrl get() = "https://raw.githubusercontent.com/$owner/$repo/$ref/${prefix()}${SkillParser.FILE_NAME}"
        val fallbackName get() = path.substringAfterLast('/').ifEmpty { repo }

        /** The contents API listing of the folder, or of [sub] inside it. */
        fun contentsUrl(sub: String = ""): String {
            val full = listOf(path, sub).filter { it.isNotEmpty() }.joinToString("/")
            return "https://api.github.com/repos/$owner/$repo/contents/$full?ref=$ref"
        }

        private fun prefix() = if (path.isEmpty()) "" else "$path/"
    }

    companion object {
        /**
         * Understands raw links to SKILL.md or .zip files and GitHub page links:
         * `github.com/<owner>/<repo>` (the whole repository as an archive), `…/tree/<ref>/<path>` and
         * `…/blob/<ref>/<path>/SKILL.md`. A branch name with a slash in it cannot be told apart from
         * a path, so the ref is taken to be a single segment. Returns null for anything else.
         */
        fun parse(link: String): SkillLink? {
            val text = link.trim().let { if ("://" in it) it else "https://$it" }
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            if (uri.scheme != "https" && uri.scheme != "http") return null
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            val last = segments.last()

            return when (host) {
                "github.com" -> {
                    if (segments.size < 2) return null
                    val owner = segments[0]
                    val repo = segments[1].removeSuffix(".git")
                    val rest = segments.drop(4).joinToString("/")
                    when (segments.getOrNull(2)) {
                        null -> Download("https://codeload.github.com/$owner/$repo/zip/HEAD", repo)
                        "tree" -> segments.getOrNull(3)?.let { GithubFolder(owner, repo, it, rest) }
                        "blob", "raw" -> when {
                            segments.size < 5 -> null
                            last.equals(SkillParser.FILE_NAME, ignoreCase = true) ->
                                GithubFolder(owner, repo, segments[3], rest.substringBeforeLast('/', ""))
                            else -> Download("https://raw.githubusercontent.com/$owner/$repo/${segments[3]}/$rest", baseName(last))
                        }
                        "archive", "releases" -> Download(text, repo)
                        else -> null
                    }
                }
                "raw.githubusercontent.com" -> {
                    // Both /owner/repo/main/… and /owner/repo/refs/heads/main/… are in use.
                    val refAt = if (segments.getOrNull(2) == "refs" && segments.size > 5) 4 else 2
                    if (segments.size > refAt + 1 && last.equals(SkillParser.FILE_NAME, ignoreCase = true)) {
                        val folder = segments.subList(refAt + 1, segments.size - 1).joinToString("/")
                        GithubFolder(segments[0], segments[1], segments[refAt], folder)
                    } else Download(text, baseName(last))
                }
                else -> {
                    val name = if (last.equals(SkillParser.FILE_NAME, ignoreCase = true)) segments.getOrNull(segments.size - 2) else null
                    Download(text, name ?: baseName(last))
                }
            }
        }

        private fun baseName(file: String) = file.substringBeforeLast('.').ifEmpty { "skill" }
    }
}

/** An entry of a GitHub contents API listing. */
data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long, val downloadUrl: String?)

object GithubContents {
    fun parse(json: String): List<RemoteEntry> {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            throw IOException("Unexpected answer from GitHub", e)
        }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RemoteEntry(
                name = name,
                isDirectory = item.optString("type") == "dir",
                size = item.optLong("size"),
                downloadUrl = item.optString("download_url").takeIf { it.startsWith("https://raw.githubusercontent.com/") },
            )
        }
    }

    /**
     * The entries of a folder listing that [skillMd] mentions: files by name, folders as `name/`.
     * Fetching only these keeps a skill that lives in a big folder small.
     */
    fun referenced(entries: List<RemoteEntry>, skillMd: String): List<RemoteEntry> = entries.filter { entry ->
        when {
            entry.name.equals(SkillParser.FILE_NAME, ignoreCase = true) || entry.name.startsWith(".") -> false
            entry.isDirectory -> "${entry.name}/" in skillMd
            else -> entry.downloadUrl != null && entry.size <= SkillArchive.MAX_FILE_BYTES && entry.name in skillMd
        }
    }
}

fun interface SkillHttp {
    /** The body of [url]; fails on a non-success status or when the body is larger than [maxBytes]. */
    fun get(url: String, maxBytes: Int): ByteArray
}

object UrlSkillHttp : SkillHttp {
    override fun get(url: String, maxBytes: Int): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ModulAI")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code from ${conn.url.host}")
            val bytes = conn.inputStream.use { it.readAtMost(maxBytes + 1) }
            if (bytes.size > maxBytes) throw IOException("The file is too large")
            return bytes
        } finally {
            conn.disconnect()
        }
    }
}

/** Downloads a skill from a link and puts it into [store]. */
class SkillInstaller(private val store: SkillStore, private val http: SkillHttp = UrlSkillHttp) {
    suspend fun fromLink(link: String): Skill = withContext(Dispatchers.IO) {
        when (val parsed = SkillLink.parse(link) ?: throw SkillFormatException("Not a link to a skill")) {
            is SkillLink.Download ->
                store.install(ByteArrayInputStream(http.get(parsed.url, MAX_DOWNLOAD_BYTES)), parsed.fallbackName)
            is SkillLink.GithubFolder -> {
                val skillMd = String(http.get(parsed.skillUrl, SkillArchive.MAX_FILE_BYTES), Charsets.UTF_8)
                store.install(SkillBundle(skillMd, extraFiles(parsed, skillMd), parsed.fallbackName))
            }
        }
    }

    /**
     * Files next to SKILL.md that it refers to, one folder level deep. The listing needs the
     * GitHub API, which is rate limited without a token; when it fails the skill installs without them.
     */
    private fun extraFiles(folder: SkillLink.GithubFolder, skillMd: String): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        fun collect(sub: String, depth: Int) {
            val listing = GithubContents.parse(String(http.get(folder.contentsUrl(sub), MAX_LISTING_BYTES), Charsets.UTF_8))
            // Inside a folder the instructions point at, every small file is taken.
            val wanted = if (depth == 0) GithubContents.referenced(listing, skillMd) else listing.filter {
                !it.isDirectory && it.downloadUrl != null && it.size <= SkillArchive.MAX_FILE_BYTES
            }
            for (entry in wanted) {
                if (files.size >= SkillArchive.MAX_EXTRA_FILES) return
                val path = if (sub.isEmpty()) entry.name else "$sub/${entry.name}"
                if (entry.isDirectory) {
                    if (depth == 0) collect(path, 1)
                } else {
                    val bytes = runCatching { http.get(entry.downloadUrl!!, SkillArchive.MAX_FILE_BYTES) }.getOrNull()
                    if (bytes != null && !bytes.contains(0)) files[path] = bytes
                }
            }
        }
        try {
            collect("", 0)
        } catch (e: IOException) {
            // Keep whatever was fetched before the API stopped answering.
        }
        return files
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024
        const val MAX_LISTING_BYTES = 512 * 1024
    }
}
