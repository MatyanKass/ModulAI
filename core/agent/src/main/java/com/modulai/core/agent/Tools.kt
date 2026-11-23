package com.modulai.core.agent

import com.modulai.core.runtime.ToolSpec
import org.json.JSONObject

/** Something the model can call. [run] returns the text handed back to the model. */
interface Tool {
    val spec: ToolSpec
    suspend fun run(args: JSONObject, log: AgentLog): String
}

class WebSearchTool(private val web: Web) : Tool {
    override val spec = ToolSpec(
        "web_search",
        "Search the web. Returns numbered results with title, link and a short snippet. " +
            "Use it for anything recent or anything you are not sure about.",
        """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return "Error: empty query"
        log.add(LogKind.SEARCH, query)
        val results = web.search(query)
        val text = results.mapIndexed { i, r -> "${i + 1}. ${r.title}\n${r.url}\n${r.snippet}" }.joinToString("\n\n")
        log.add(LogKind.TOOL_RESULT, "${results.size} results", text)
        return text
    }
}

class FetchPageTool(private val web: Web, private val maxChars: Int = 6000) : Tool {
    override val spec = ToolSpec(
        "fetch_page",
        "Open a web page by its link and return its text. Use it after web_search to read a result in full.",
        """{"type":"object","properties":{"url":{"type":"string","description":"Full page address"}},"required":["url"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val url = args.optString("url").trim()
        if (url.isEmpty()) return "Error: empty url"
        val page = web.fetch(url, maxChars)
        log.add(LogKind.PAGE, page.title.ifEmpty { url }, "$url\n${page.lastModified.orEmpty()}\n\n${page.text}")
        val updated = page.lastModified?.let { "\nLast modified: $it" }.orEmpty()
        return "${page.title}$updated\n\n${page.text}"
    }
}
