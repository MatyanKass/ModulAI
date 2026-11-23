package com.modulai.core.skills.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.Base64

/** A string field, or null when it is absent or JSON null (Android's optString turns null into "null"). */
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

/** Server-Sent Events, as far as MCP uses them: each event's `data` is one JSON-RPC message. */
internal object Sse {
    /**
     * Feeds the data of each event to [pick] and returns its first non-null answer, without waiting
     * for the stream to end: a server may keep it open after the response. Null when the stream ends first.
     * Gives up when the stream grows past [maxChars] or is still going at [deadlineNanos].
     */
    fun <T : Any> first(reader: BufferedReader, maxChars: Int, deadlineNanos: Long = Long.MAX_VALUE, pick: (String) -> T?): T? {
        val data = StringBuilder()
        var hasData = false
        var total = 0
        while (true) {
            val line = reader.readLine()
            if (line != null) {
                total += line.length + 1
                if (total > maxChars) throw IOException("The response is too large")
                if (System.nanoTime() > deadlineNanos) throw IOException("The server took too long to answer")
            }
            when {
                // A blank line ends the event; the end of the stream ends an unfinished one too.
                line.isNullOrEmpty() -> {
                    if (hasData) pick(data.toString())?.let { return it }
                    if (line == null) return null
                    data.clear()
                    hasData = false
                }
                line.startsWith("data:") -> {
                    if (hasData) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                    hasData = true
                }
                // Comments (":…"), event names, ids and retry hints carry nothing needed here.
            }
        }
    }
}

/** A tool argument the server wants repeated in an `Mcp-Param-<header>` HTTP header. */
data class HeaderParam(val path: List<String>, val header: String)

/** The `x-mcp-header` schema extension and header value encoding of the Streamable HTTP transport. */
internal object McpHeaders {
    private const val KEY = "x-mcp-header"
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val PRIMITIVES = setOf("string", "integer", "boolean")

    /**
     * The annotated parameters of a tool's input schema. Throws when an annotation breaks the rules
     * of the specification; such a tool has to be left out of the tool list.
     */
    fun params(schema: JSONObject): List<HeaderParam> {
        val found = ArrayList<HeaderParam>()
        walk(schema, emptyList(), reachable = true, found)
        if (found.map { it.header.lowercase() }.toSet().size != found.size) throw IllegalArgumentException("duplicate $KEY")
        return found
    }

    /** [reachable] holds while the path from the root went through `properties` keys only. */
    private fun walk(node: Any?, path: List<String>, reachable: Boolean, found: MutableList<HeaderParam>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), path, false, found)
            is JSONObject -> {
                if (node.has(KEY)) {
                    val name = node.opt(KEY) as? String
                    val valid = reachable && path.isNotEmpty() && name != null && TOKEN.matches(name) &&
                        (node.opt("type") as? String) in PRIMITIVES
                    if (!valid) throw IllegalArgumentException("invalid $KEY at ${path.joinToString(".")}")
                    found += HeaderParam(path, name!!)
                }
                for (key in node.keys()) {
                    val child = node.opt(key)
                    if (key == "properties" && child is JSONObject) {
                        // The keys here are parameter names, not schema keywords.
                        for (property in child.keys()) walk(child.opt(property), path + property, reachable, found)
                    } else if (key != KEY) {
                        walk(child, path, false, found)
                    }
                }
            }
        }
    }

    /** The header value for the argument at [path], or null when it is absent, null or not a primitive. */
    fun valueAt(arguments: JSONObject, path: List<String>): String? {
        var node: Any? = arguments
        for (key in path) node = (node as? JSONObject)?.opt(key)
        return when (val value = node) {
            is String -> encode(value)
            is Boolean -> value.toString()
            is Int, is Long -> value.toString()
            is Number -> value.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()?.toString()
            else -> null
        }
    }

    /** Values that are not plain header-safe ASCII travel as `=?base64?…?=`. */
    fun encode(value: String): String {
        val plain = value.all { it.code in 0x20..0x7E } && value == value.trim() &&
            !(value.startsWith("=?base64?") && value.endsWith("?="))
        return if (plain) value else "=?base64?" + Base64.getEncoder().encodeToString(value.toByteArray()) + "?="
    }
}

internal object McpResults {
    /**
     * A `tools/call` result as text for the model: text items joined, everything else named in
     * brackets, a failed call prefixed with "Error: ". Never longer than [maxChars] plus a short note.
     */
    fun flatten(result: JSONObject, maxChars: Int): String {
        val content = result.optJSONArray("content") ?: JSONArray()
        val parts = (0 until content.length()).mapNotNull { i ->
            val item = content.optJSONObject(i) ?: return@mapNotNull null
            when (val type = item.str("type")) {
                "text" -> item.str("text")
                "resource" -> item.optJSONObject("resource")?.let { it.str("text") ?: "[resource: ${it.str("uri").orEmpty()}]" }
                "resource_link" -> "[resource: ${item.str("uri").orEmpty()}]"
                null -> null
                else -> "[$type]"
            }
        }.filter { it.isNotBlank() }

        // Structured output normally comes with a text copy; use it only when that is missing.
        val text = parts.joinToString("\n").ifEmpty {
            if (result.isNull("structuredContent")) "" else result.get("structuredContent").toString()
        }.ifEmpty { "(empty result)" }
        val bounded = if (text.length <= maxChars) text else text.take(maxChars) + "\n[cut: the result is longer than $maxChars characters]"
        return if (result.optBoolean("isError")) "Error: $bounded" else bounded
    }
}
