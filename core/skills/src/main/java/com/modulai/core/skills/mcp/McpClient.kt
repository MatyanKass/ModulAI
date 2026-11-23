package com.modulai.core.skills.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Anything that went wrong talking to an MCP server, with a message fit to show to the user. */
class McpException(message: String, internal val code: Int? = null) : IOException(message)

data class McpTool(
    val name: String,
    val description: String,
    /** JSON Schema of the arguments, as JSON text. */
    val inputSchema: String,
    val headerParams: List<HeaderParam> = emptyList(),
)

/**
 * Model Context Protocol client for the Streamable HTTP transport, tools only.
 *
 * Speaks protocol revision 2026-07-28, where every request stands alone and carries its version
 * and client info in `_meta` and in HTTP headers. Most servers in the wild still implement the
 * handshake-based revisions, so the client follows the specification's backward compatibility
 * rule: when the first request is rejected without a recognised 2026-07-28 error, it falls back to
 * `initialize` → `notifications/initialized` with revision 2025-11-25 (or the older one the server
 * picks) and keeps the session id the server hands out.
 */
class McpClient(
    private val config: McpServerConfig,
    private val clientName: String = "ModulAI",
    private val clientVersion: String = "1.0",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 60_000,
) {
    private enum class Era { MODERN, LEGACY }

    private val ids = AtomicInteger()
    private var era: Era? = null
    private var legacyVersion: String? = null
    private var sessionId: String? = null
    @Volatile private var active: HttpURLConnection? = null

    /** The name the server gives itself; known after the first successful request. */
    @Volatile var serverName: String? = null
        private set

    /** The protocol revision in use; known after the first successful request. */
    val protocolVersion: String? get() = when (era) {
        Era.MODERN -> MODERN_VERSION
        Era.LEGACY -> legacyVersion
        null -> null
    }

    /** All tools of the server, following pagination. Tools with a malformed definition are left out. */
    suspend fun listTools(): List<McpTool> = io {
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = request("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } }, listing = true)
            val page = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until page.length()) parseTool(page.optJSONObject(i))?.let { tools += it }
            cursor = result.str("nextCursor")?.takeIf { it.isNotEmpty() }
        } while (cursor != null && ++pages < MAX_PAGES && tools.size < MAX_TOOLS)
        tools.take(MAX_TOOLS)
    }

    /** Runs [tool] and returns the raw `tools/call` result; see [McpResults.flatten] for turning it into text. */
    suspend fun callTool(tool: McpTool, arguments: JSONObject): JSONObject = io {
        val result = request("tools/call", JSONObject().put("name", tool.name).put("arguments", arguments), tool = tool)
        if (result.str("resultType") == "input_required") {
            throw McpException("The tool asked for additional input from the user, which is not supported")
        }
        result
    }

    /**
     * Runs blocking network code so that cancelling the caller takes effect at once: a blocked
     * socket read ignores cancellation, so a watcher closes the connection instead.
     */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                active?.disconnect()
            }
        }
        try {
            block()
        } catch (e: McpException) {
            ensureActive()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    private fun parseTool(json: JSONObject?): McpTool? {
        val name = json?.str("name")?.takeIf { it.isNotEmpty() } ?: return null
        val schema = json.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject())
        // The specification requires leaving out a tool whose header annotations are invalid.
        val headers = try {
            McpHeaders.params(schema)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return McpTool(name, json.str("description") ?: json.str("title").orEmpty(), schema.toString(), headers)
    }

    /** One request at a time: the handshake state and the session id are shared. */
    @Synchronized
    private fun request(method: String, params: JSONObject, tool: McpTool? = null, listing: Boolean = false): JSONObject {
        val timeout = if (listing) minOf(readTimeoutMs, LIST_TIMEOUT_MS) else readTimeoutMs
        try {
            return when (era) {
                Era.MODERN -> modern(method, params, tool, timeout).result()
                Era.LEGACY -> legacy(method, params, timeout)
                null -> {
                    val reply = modern(method, params, tool, timeout)
                    if (!reply.pointsToLegacyServer()) {
                        reply.result().also { era = Era.MODERN }
                    } else {
                        try {
                            initialize()
                        } catch (e: McpException) {
                            // A server of the current revision has no handshake and rejects it like this;
                            // then its answer to the first request is the error worth showing.
                            val modernServer = e.code == METHOD_NOT_FOUND || e.code == INVALID_PARAMS || e.code == HEADER_MISMATCH
                            throw if (modernServer) reply.failure() ?: e else e
                        }
                        era = Era.LEGACY
                        legacy(method, params, timeout)
                    }
                }
            }
        } catch (e: McpException) {
            throw e
        } catch (e: IOException) {
            throw McpException(redact("Cannot reach ${host()}: ${e.message ?: e.javaClass.simpleName}"))
        } catch (e: JSONException) {
            throw McpException("${host()} sent a response that is not valid MCP")
        } catch (e: IllegalArgumentException) {
            // HttpURLConnection rejects header names and values with illegal characters.
            throw McpException("The address or the authentication header of this server is not valid")
        }
    }

    private fun modern(method: String, params: JSONObject, tool: McpTool?, timeout: Int): Reply {
        val meta = JSONObject()
            .put("io.modelcontextprotocol/protocolVersion", MODERN_VERSION)
            .put("io.modelcontextprotocol/clientInfo", clientInfo())
            .put("io.modelcontextprotocol/clientCapabilities", JSONObject())
        // The transport mirrors the routing fields of the body into headers; servers reject a mismatch.
        val headers = LinkedHashMap<String, String>()
        headers["MCP-Protocol-Version"] = MODERN_VERSION
        headers["Mcp-Method"] = method
        if (tool != null) {
            headers["Mcp-Name"] = McpHeaders.encode(tool.name)
            val arguments = params.optJSONObject("arguments") ?: JSONObject()
            for (param in tool.headerParams) {
                McpHeaders.valueAt(arguments, param.path)?.let { headers["Mcp-Param-${param.header}"] = it }
            }
        }
        // Copied so that a fallback to the handshake does not send the metadata of this revision.
        val body = JSONObject(params.toString()).put("_meta", meta)
        val reply = post(rpc(method, body), headers, timeout)
        reply.message?.optJSONObject("result")?.optJSONObject("_meta")
            ?.optJSONObject("io.modelcontextprotocol/serverInfo")?.str("name")?.let { serverName = it }
        return reply
    }

    private fun legacy(method: String, params: JSONObject, timeout: Int): JSONObject {
        val reply = post(rpc(method, params), legacyHeaders(), timeout)
        // 404 for a request with a session id means the session expired: start a new one.
        if (reply.status == 404 && sessionId != null) {
            initialize()
            return post(rpc(method, params), legacyHeaders(), timeout).result()
        }
        return reply.result()
    }

    private fun initialize() {
        sessionId = null
        val params = JSONObject()
            .put("protocolVersion", LEGACY_VERSIONS.first())
            .put("capabilities", JSONObject())
            .put("clientInfo", clientInfo())
        val reply = post(rpc("initialize", params), emptyMap(), minOf(readTimeoutMs, LIST_TIMEOUT_MS))
        val result = reply.result()
        val version = result.str("protocolVersion").orEmpty()
        if (version !in LEGACY_VERSIONS) {
            throw McpException("The server uses MCP revision \"$version\", which this app does not support")
        }
        legacyVersion = version
        sessionId = reply.sessionId
        serverName = result.optJSONObject("serverInfo")?.str("name")

        val ack = post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), legacyHeaders(), LIST_TIMEOUT_MS)
        if (ack.status >= 400) throw McpException("The server did not accept the connection (HTTP ${ack.status})")
    }

    private fun legacyHeaders(): Map<String, String> = buildMap {
        legacyVersion?.let { put("MCP-Protocol-Version", it) }
        sessionId?.let { put("Mcp-Session-Id", it) }
    }

    private fun clientInfo() = JSONObject().put("name", clientName).put("version", clientVersion)

    private fun rpc(method: String, params: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", method).put("params", params)

    private inner class Reply(val status: Int, val message: JSONObject?, val sessionId: String?) {
        private val error get() = message?.optJSONObject("error")

        /** The JSON-RPC error in this reply as an exception, or null when it carries none. */
        fun failure(): McpException? = error?.let { e ->
            val text = e.str("message")?.takeIf { it.isNotBlank() }?.take(MAX_ERROR_CHARS) ?: "The server reported an error"
            McpException("${redact(text)} (code ${e.optInt("code")})", e.optInt("code"))
        }

        fun result(): JSONObject {
            if (status == 401 || status == 403) {
                throw McpException("The server refused access (HTTP $status). Check the authentication header.")
            }
            message?.optJSONObject("result")?.let { return it }
            failure()?.let { throw it }
            throw McpException(if (status in 200..299) "The server sent no response to the request" else "The server answered HTTP $status")
        }

        /**
         * True when the first request failed in a way a 2026-07-28 server would not produce, so the
         * server is probably waiting for an `initialize` handshake.
         */
        fun pointsToLegacyServer(): Boolean {
            if (message?.has("result") == true || status == 401 || status == 403 || status >= 500) return false
            val e = error ?: return status in 400..499
            return when (e.optInt("code")) {
                UNSUPPORTED_VERSION -> {
                    val supported = e.optJSONObject("data")?.optJSONArray("supported") ?: JSONArray()
                    (0 until supported.length()).any { supported.optString(it) in LEGACY_VERSIONS }
                }
                HEADER_MISMATCH, MISSING_CAPABILITY -> false
                // Unknown methods get 404 from a modern server, and tools/list is never unknown to one.
                else -> true
            }
        }
    }

    private fun post(body: JSONObject, headers: Map<String, String>, timeout: Int): Reply {
        val url = URL(config.url.trim())
        if (url.protocol != "https" && url.protocol != "http") throw McpException("The server address must start with https://")
        val conn = url.openConnection() as HttpURLConnection
        active = conn
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = timeout
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            config.authHeader()?.let { (name, value) ->
                // Checked here because platforms differ in what their HTTP stack lets through.
                if (!HEADER_NAME.matches(name) || value.any { it.code < 0x20 || it.code == 0x7F }) {
                    throw McpException("The authentication header of this server is not valid")
                }
                conn.setRequestProperty(name, value)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = conn.responseCode
            val session = conn.getHeaderField("Mcp-Session-Id")
            val stream = (if (status >= 400) conn.errorStream else conn.inputStream)
            // Notifications have no id and get no JSON-RPC answer.
            if (stream == null || !body.has("id")) return Reply(status, null, session)
            val id = body.get("id").toString()
            val message = stream.bufferedReader().use { reader ->
                if (conn.contentType.orEmpty().lowercase().startsWith("text/event-stream")) {
                    // A stream of keep-alive comments never trips the read timeout, hence the deadline.
                    val deadline = System.nanoTime() + timeout * STREAM_TIMEOUT_FACTOR * 1_000_000L
                    Sse.first(reader, MAX_RESPONSE_CHARS, deadline) { data -> answerTo(id, data) }
                } else {
                    val text = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        text.append(buffer, 0, n)
                        if (text.length > MAX_RESPONSE_CHARS) throw McpException("The response is too large")
                    }
                    answerTo(id, text.toString())
                }
            }
            return Reply(status, message, session)
        } finally {
            active = null
            conn.disconnect()
        }
    }

    /**
     * The JSON-RPC response to request [id] found in [text], if any. Notifications and server
     * requests that share an SSE stream with it are skipped. An error without an id counts:
     * that is how servers reject a request they could not parse or route.
     */
    private fun answerTo(id: String, text: String): JSONObject? {
        val trimmed = text.trim()
        val candidates = when {
            trimmed.startsWith("{") -> listOfNotNull(runCatching { JSONObject(trimmed) }.getOrNull())
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
            else -> emptyList()
        }
        return candidates.firstOrNull { message ->
            val isResponse = message.has("result") || message.has("error")
            isResponse && !message.has("method") && (message.isNull("id") && message.has("error") || message.opt("id")?.toString() == id)
        }
    }

    private fun host(): String = runCatching { URL(config.url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "the server"

    /** Applied to text that comes from outside: a server can echo request headers in its error message. */
    private fun redact(text: String): String {
        val secret = config.headerValue.trim()
        return if (secret.isEmpty()) text else text.replace(secret, "***")
    }

    companion object {
        /** The revision this client speaks first. */
        const val MODERN_VERSION = "2026-07-28"

        /** Handshake-based revisions accepted in the fallback, newest first. */
        val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val HEADER_MISMATCH = -32020
        private const val MISSING_CAPABILITY = -32021
        private const val UNSUPPORTED_VERSION = -32022

        private const val MAX_PAGES = 20
        private const val MAX_TOOLS = 200
        private const val MAX_RESPONSE_CHARS = 2_000_000
        private const val MAX_ERROR_CHARS = 300
        private const val LIST_TIMEOUT_MS = 20_000
        private const val STREAM_TIMEOUT_FACTOR = 3L
    }
}
