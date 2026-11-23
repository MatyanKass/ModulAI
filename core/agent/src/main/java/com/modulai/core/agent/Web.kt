package com.modulai.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

data class SearchResult(val title: String, val url: String, val snippet: String)

data class Page(val url: String, val title: String, val text: String, val lastModified: String?)

interface Web {
    suspend fun search(query: String, limit: Int = 8): List<SearchResult>
    suspend fun fetch(url: String, maxChars: Int = 20_000): Page
}

/** Search through DuckDuckGo's no-JavaScript pages and plain HTTP page fetching. No API key. */
class HttpWeb : Web {
    override suspend fun search(query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = get("https://html.duckduckgo.com/html/?q=$q").body
        val results = Html.parseDuckDuckGo(html).ifEmpty {
            Html.parseDuckDuckGoLite(get("https://lite.duckduckgo.com/lite/?q=$q").body)
        }
        if (results.isEmpty()) throw IOException("The search engine returned no results (it may be rate limiting)")
        results.take(limit)
    }

    override suspend fun fetch(url: String, maxChars: Int): Page = withContext(Dispatchers.IO) {
        val response = get(url)
        if (!response.contentType.contains("html") && !response.contentType.contains("text")) {
            throw IOException("Not a text page: ${response.contentType}")
        }
        Page(url, Html.title(response.body), Html.toText(response.body).take(maxChars), response.lastModified)
    }

    private class Response(val body: String, val contentType: String, val lastModified: String?)

    private fun get(address: String): Response {
        val url = URL(address)
        if (url.protocol != "https" && url.protocol != "http") throw IOException("Unsupported address: $address")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            conn.setRequestProperty("Accept-Language", "ru,en;q=0.8")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${url.host}")
            val type = conn.contentType.orEmpty().lowercase()
            val charset = Regex("""charset=([\w-]+)""").find(type)?.groupValues?.get(1)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            // Pages can be huge; nothing past a couple of megabytes is worth reading on a phone.
            // InputStream.readNBytes(int) only exists from Android 13, so read the capped amount by hand.
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return Response(String(bytes, charset), type, conn.getHeaderField("Last-Modified"))
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BYTES = 2_000_000
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}

/** Regex-level HTML handling: enough to turn a page into readable text for a model. */
object Html {
    private val DROP_BLOCKS = Regex(
        """<(script|style|noscript|svg|head|nav|footer|form|iframe)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENTS = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val BREAKS = Regex("""<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…",
    )

    fun title(html: String): String =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()

    fun toText(html: String): String {
        var s = COMMENTS.replace(html, " ")
        s = DROP_BLOCKS.replace(s, " ")
        s = BREAKS.replace(s, "\n")
        s = decode(TAGS.replace(s, " "))
        return s.lines().map { it.replace(Regex("""[ \t ]+"""), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun decode(text: String): String = ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name]
        } ?: m.value
    }

    private fun codePoint(cp: Int) = if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null

    private val DDG_LINK = Regex(
        """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val DDG_SNIPPET = Regex(
        """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(a|div|td)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val LITE_LINK = Regex(
        """<a[^>]*href="([^"]+)"[^>]*class=['"]result-link['"][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val LITE_SNIPPET = Regex(
        """class=['"]result-snippet['"][^>]*>(.*?)</td>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parseDuckDuckGo(html: String) = pair(html, DDG_LINK, DDG_SNIPPET)

    fun parseDuckDuckGoLite(html: String) = pair(html, LITE_LINK, LITE_SNIPPET)

    private fun pair(html: String, link: Regex, snippet: Regex): List<SearchResult> {
        val links = link.findAll(html).toList()
        return links.mapIndexedNotNull { i, m ->
            val url = resolveRedirect(decode(m.groupValues[1])) ?: return@mapIndexedNotNull null
            // A snippet belongs to a link if it sits between it and the next link.
            val end = links.getOrNull(i + 1)?.range?.first ?: html.length
            val s = snippet.find(html, m.range.last)?.takeIf { it.range.first < end }
            SearchResult(clean(m.groupValues[2]), url, s?.let { clean(it.groupValues[1]) }.orEmpty())
        }.distinctBy { it.url }
    }

    private fun clean(fragment: String) = decode(TAGS.replace(fragment, "")).replace(Regex("""\s+"""), " ").trim()

    /** DuckDuckGo wraps targets as //duckduckgo.com/l/?uddg=<encoded url>; ads are dropped. */
    private fun resolveRedirect(href: String): String? {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
        val url = if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else href
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if ("duckduckgo.com/y.js" in url) return null
        return url
    }
}
