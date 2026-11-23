package com.modulai.core.skills.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A remote MCP server. [headerValue] is a secret. With an empty [headerName] it is sent as
 * `Authorization: Bearer <value>`, otherwise as the value of that header.
 */
data class McpServerConfig(
    /** Unique among the servers, made of [a-z0-9_]; prefixes the names of the server's tools. */
    val id: String,
    val name: String,
    val url: String,
    val headerName: String = "",
    val headerValue: String = "",
    val enabled: Boolean = true,
) {
    /** The authentication header to send, or null when no secret is set. */
    fun authHeader(): Pair<String, String>? {
        val value = headerValue.trim()
        if (value.isEmpty()) return null
        val name = headerName.trim()
        return when {
            name.isNotEmpty() -> name to value
            value.startsWith("Bearer ", ignoreCase = true) -> "Authorization" to value
            else -> "Authorization" to "Bearer $value"
        }
    }

    // The generated toString would put the secret into logs and crash reports.
    override fun toString() = "McpServerConfig(id=$id, name=$name, enabled=$enabled)"
}

/** The user's MCP servers, kept in `servers.json` under [root]. Call off the main thread. */
class McpStore(private val root: File) {
    private val file = File(root, "servers.json")

    @Synchronized
    fun list(): List<McpServerConfig> {
        val array = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            McpServerConfig(
                id = item.str("id") ?: return@mapNotNull null,
                name = item.str("name").orEmpty(),
                url = item.str("url").orEmpty(),
                headerName = item.str("headerName").orEmpty(),
                headerValue = item.str("headerValue").orEmpty(),
                enabled = item.optBoolean("enabled", true),
            )
        }
    }

    /** Adds [config], or replaces the server with the same id. A blank id gets one made from the name. */
    @Synchronized
    fun save(config: McpServerConfig): McpServerConfig {
        val servers = list()
        val saved = if (config.id.isNotBlank()) config else config.copy(id = newId(config.name, servers.map { it.id }))
        write(if (servers.any { it.id == saved.id }) servers.map { if (it.id == saved.id) saved else it } else servers + saved)
        return saved
    }

    @Synchronized
    fun delete(id: String) = write(list().filterNot { it.id == id })

    private fun write(servers: List<McpServerConfig>) {
        root.mkdirs()
        val array = JSONArray()
        for (s in servers) {
            array.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url)
                    .put("headerName", s.headerName).put("headerValue", s.headerValue)
                    .put("enabled", s.enabled),
            )
        }
        file.writeText(array.toString())
    }

    companion object {
        /** [text] reduced to the characters allowed in a tool name prefix. */
        fun sanitizeId(text: String): String =
            text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).trim('_')

        internal fun newId(name: String, taken: Collection<String>): String {
            val base = sanitizeId(name).ifEmpty { "server" }
            return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }
        }
    }
}
