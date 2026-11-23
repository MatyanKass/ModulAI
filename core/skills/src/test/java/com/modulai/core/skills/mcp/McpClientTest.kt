package com.modulai.core.skills.mcp

import com.modulai.core.agent.AgentLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private class RpcFailure(val code: Int, override val message: String) : Exception()

/**
 * One HTTP request on its own connection. The JDK's built-in HTTP server is not on the compile
 * classpath of an Android module, and this much of HTTP/1.1 is all the tests need.
 */
private class HttpExchange(private val socket: Socket) : Closeable {
    val requestMethod: String
    val requestHeaders = HashMap<String, String>()
    val requestBody: ByteArrayInputStream
    val responseHeaders = ArrayList<Pair<String, String>>()
    val responseBody: OutputStream = socket.getOutputStream()

    init {
        val input = socket.getInputStream()
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) bytes += b.toByte()
            }
            return String(bytes.toByteArray())
        }
        requestMethod = line().substringBefore(' ')
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            requestHeaders[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
        }
        val body = ByteArray(requestHeaders["content-length"]?.toInt() ?: 0)
        var read = 0
        while (read < body.size) read += input.read(body, read, body.size - read)
        requestBody = ByteArrayInputStream(body)
    }

    /** [length] -1 sends no body, 0 a body that ends when the connection closes. */
    fun sendResponseHeaders(status: Int, length: Long) {
        val head = StringBuilder("HTTP/1.1 $status Status\r\nConnection: close\r\n")
        if (length != 0L) head.append("Content-Length: ${maxOf(length, 0)}\r\n")
        for ((name, value) in responseHeaders) head.append("$name: $value\r\n")
        responseBody.write(head.append("\r\n").toString().toByteArray())
        responseBody.flush()
    }

    override fun close() = socket.close()
}

private class Received(val method: String, val headers: Map<String, String>, val body: JSONObject)

/**
 * An in-process MCP server over real HTTP. In the modern mode it applies the request validation of
 * revision 2026-07-28; in the legacy mode it insists on the initialize handshake and a session id.
 */
private class FakeServer(
    val modern: Boolean,
    val sse: Boolean = false,
    val requiredAuth: Pair<String, String>? = null,
    val pageSize: Int = 100,
    val legacyVersion: String = "2025-11-25",
) {
    val received = CopyOnWriteArrayList<Received>()
    val sessions = HashSet<String>()
    var holdStreamOpenMs = 0L
    var hangMs = 0L
    val tools = ArrayList<JSONObject>()
    var call: (name: String, arguments: JSONObject) -> JSONObject = { name, arguments ->
        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "$name:$arguments")))
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val url get() = "http://127.0.0.1:${socket.localPort}/mcp"

    init {
        workers.execute {
            while (true) {
                val connection = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                workers.execute {
                    // The client hanging up mid-response is part of some tests.
                    runCatching { HttpExchange(connection).use { handle(it) } }
                }
            }
        }
    }

    fun stop() {
        socket.close()
        workers.shutdownNow()
    }

    fun tool(name: String, schema: String = """{"type":"object","properties":{"q":{"type":"string"}}}""") {
        tools += JSONObject().put("name", name).put("description", "Tool $name").put("inputSchema", JSONObject(schema))
    }

    fun methods() = received.map { it.method }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return exchange.sendResponseHeaders(405, -1)
        val headers = exchange.requestHeaders
        val body = JSONObject(String(exchange.requestBody.readBytes()))
        val method = body.optString("method")
        received += Received(method, headers, body)
        if (hangMs > 0) Thread.sleep(hangMs)

        requiredAuth?.let { (name, value) ->
            // Echoes what it got, like a careless server would.
            if (headers[name.lowercase()] != value) return error(exchange, 401, body, -32001, "Bad credentials: ${headers[name.lowercase()]}")
        }
        if (!headers["accept"].orEmpty().let { "application/json" in it && "text/event-stream" in it }) {
            return error(exchange, 406, body, -32600, "Not Acceptable")
        }
        if (modern) handleModern(exchange, headers, body, method) else handleLegacy(exchange, headers, body, method)
    }

    private fun handleModern(exchange: HttpExchange, headers: Map<String, String>, body: JSONObject, method: String) {
        val params = body.optJSONObject("params") ?: JSONObject()
        val meta = params.optJSONObject("_meta")
        val version = headers["mcp-protocol-version"]
        when {
            version == null || headers["mcp-method"] != method -> return error(exchange, 400, body, -32020, "Header mismatch")
            meta == null || !meta.has("io.modelcontextprotocol/clientCapabilities") -> return error(exchange, 400, body, -32602, "Invalid params")
            meta.optString("io.modelcontextprotocol/protocolVersion") != version -> return error(exchange, 400, body, -32020, "Header mismatch: version")
            version != "2026-07-28" -> return error(exchange, 400, body, -32022, "Unsupported protocol version")
        }
        val result = when (method) {
            "tools/list" -> listResult(params)
            "tools/call" -> {
                val sent = headers["mcp-name"].orEmpty()
                val name = if (sent.startsWith("=?base64?")) String(Base64.getDecoder().decode(sent.removePrefix("=?base64?").removeSuffix("?="))) else sent
                if (name != params.optString("name")) return error(exchange, 400, body, -32020, "Header mismatch: Mcp-Name")
                try {
                    call(name, params.optJSONObject("arguments") ?: JSONObject())
                } catch (e: RpcFailure) {
                    return error(exchange, 200, body, e.code, e.message)
                }
            }
            else -> return error(exchange, 404, body, -32601, "Method not found")
        }
        result.put("resultType", result.optString("resultType", "complete"))
        result.put("_meta", JSONObject().put("io.modelcontextprotocol/serverInfo", JSONObject().put("name", "ModernFake").put("version", "1")))
        respond(exchange, body, result)
    }

    private fun handleLegacy(exchange: HttpExchange, headers: Map<String, String>, body: JSONObject, method: String) {
        val params = body.optJSONObject("params") ?: JSONObject()
        if (method == "initialize") {
            val session = "session-${sessions.size + 1}"
            sessions += session
            exchange.responseHeaders += "Mcp-Session-Id" to session
            val result = JSONObject()
                .put("protocolVersion", legacyVersion)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "LegacyFake").put("version", "1"))
            return respond(exchange, body, result)
        }
        val session = headers["mcp-session-id"]
        when {
            session == null -> return error(exchange, 400, body.put("id", JSONObject.NULL), -32000, "Bad Request: Server not initialized")
            session !in sessions -> return exchange.sendResponseHeaders(404, -1)
            headers["mcp-protocol-version"] != legacyVersion -> return error(exchange, 400, body, -32000, "Bad Request: Unsupported protocol version")
            params.has("_meta") -> return error(exchange, 400, body, -32602, "Unexpected _meta")
        }
        when (method) {
            "notifications/initialized" -> exchange.sendResponseHeaders(202, -1)
            "tools/list" -> respond(exchange, body, listResult(params))
            "tools/call" -> try {
                respond(exchange, body, call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()))
            } catch (e: RpcFailure) {
                error(exchange, 200, body, e.code, e.message)
            }
            else -> error(exchange, 200, body, -32601, "Method not found")
        }
    }

    private fun listResult(params: JSONObject): JSONObject {
        val from = params.optString("cursor", "0").toInt()
        val result = JSONObject().put("tools", JSONArray(tools.drop(from).take(pageSize)))
        if (from + pageSize < tools.size) result.put("nextCursor", (from + pageSize).toString())
        return result
    }

    private fun error(exchange: HttpExchange, status: Int, request: JSONObject, code: Int, message: String) {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id") ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
        send(exchange, status, "application/json", body.toString())
    }

    private fun respond(exchange: HttpExchange, request: JSONObject, result: JSONObject) {
        val response = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result)
        if (!sse) return send(exchange, 200, "application/json", response.toString())

        val progress = """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}"""
        val other = """{"jsonrpc":"2.0","id":987654,"result":{"tools":[]}}"""
        // A priming event with empty data, a comment, CRLF line ends and a response split over two data lines.
        val text = response.toString()
        val split = text.indexOf(',') + 1
        val stream = "id: 1\r\ndata: \r\n\r\n: keep-alive\r\n\r\nevent: message\r\ndata: $progress\r\n\r\ndata: $other\r\n\r\n" +
            "event: message\r\nid: 2\r\ndata: ${text.substring(0, split)}\r\ndata: ${text.substring(split)}\r\n\r\n"
        exchange.responseHeaders += "Content-Type" to "text/event-stream"
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.write(stream.toByteArray())
        exchange.responseBody.flush()
        if (holdStreamOpenMs > 0) Thread.sleep(holdStreamOpenMs)
    }

    private fun send(exchange: HttpExchange, status: Int, type: String, text: String) {
        val bytes = text.toByteArray()
        exchange.responseHeaders += "Content-Type" to type
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}

class McpClientTest {
    private val servers = ArrayList<FakeServer>()

    private fun server(modern: Boolean, sse: Boolean = false, auth: Pair<String, String>? = null, pageSize: Int = 100, version: String = "2025-11-25") =
        FakeServer(modern, sse, auth, pageSize, version).also { servers += it }

    private fun client(server: FakeServer, headerName: String = "", headerValue: String = "", readTimeoutMs: Int = 5000) =
        McpClient(McpServerConfig("srv", "Server", server.url, headerName, headerValue), "TestClient", "9.9", 2000, readTimeoutMs)

    private fun failure(block: suspend () -> Unit): McpException = try {
        runBlocking { block() }
        fail("expected a failure")
        error("unreachable")
    } catch (e: McpException) {
        e
    }

    @After fun stop() = servers.forEach { it.stop() }

    @Test fun modernServerNeedsNoHandshake() = runBlocking {
        val server = server(modern = true)
        server.tool("search")
        server.tool("поиск")
        val client = client(server)

        val tools = client.listTools()
        assertEquals(listOf("search", "поиск"), tools.map { it.name })
        assertEquals("Tool search", tools[0].description)
        assertEquals("string", JSONObject(tools[0].inputSchema).getJSONObject("properties").getJSONObject("q").getString("type"))
        assertEquals("2026-07-28", client.protocolVersion)
        assertEquals("ModernFake", client.serverName)

        val result = client.callTool(tools[0], JSONObject().put("q", "x"))
        assertEquals("""search:{"q":"x"}""", McpResults.flatten(result, 1000))
        // A name that is not header-safe travels base64-encoded; the fake server compares it to the body.
        assertTrue(McpResults.flatten(client.callTool(tools[1], JSONObject()), 1000).startsWith("поиск:"))

        assertEquals(listOf("tools/list", "tools/call", "tools/call"), server.methods())
        val list = server.received[0]
        assertEquals("2026-07-28", list.headers["mcp-protocol-version"])
        assertEquals("tools/list", list.headers["mcp-method"])
        assertNull(list.headers["mcp-name"])
        assertNull(list.headers["mcp-session-id"])
        assertEquals("application/json", list.headers["content-type"])
        assertEquals("2.0", list.body.getString("jsonrpc"))
        val meta = list.body.getJSONObject("params").getJSONObject("_meta")
        assertEquals("2026-07-28", meta.getString("io.modelcontextprotocol/protocolVersion"))
        assertEquals("TestClient", meta.getJSONObject("io.modelcontextprotocol/clientInfo").getString("name"))
        assertEquals(0, meta.getJSONObject("io.modelcontextprotocol/clientCapabilities").length())
        assertEquals("search", server.received[1].headers["mcp-name"])
        assertEquals("=?base64?" + Base64.getEncoder().encodeToString("поиск".toByteArray()) + "?=", server.received[2].headers["mcp-name"])
        assertTrue(server.received.map { it.body.getInt("id") }.toSet().size == 3)
    }

    @Test fun legacyServerGetsHandshakeSessionAndPagination() = runBlocking {
        val server = server(modern = false, pageSize = 2)
        repeat(5) { server.tool("t$it") }
        val client = client(server)

        assertEquals(listOf("t0", "t1", "t2", "t3", "t4"), client.listTools().map { it.name })
        assertEquals("2025-11-25", client.protocolVersion)
        assertEquals("LegacyFake", client.serverName)
        // The first request is the probe of the current revision; the server turns it down with 400.
        assertEquals(
            listOf("tools/list", "initialize", "notifications/initialized", "tools/list", "tools/list", "tools/list"),
            server.methods(),
        )
        val init = server.received[1]
        assertEquals("2025-11-25", init.body.getJSONObject("params").getString("protocolVersion"))
        assertEquals("TestClient", init.body.getJSONObject("params").getJSONObject("clientInfo").getString("name"))
        assertEquals("9.9", init.body.getJSONObject("params").getJSONObject("clientInfo").getString("version"))
        assertTrue(init.body.getJSONObject("params").has("capabilities"))
        assertNull(init.headers["mcp-session-id"])
        val notification = server.received[2]
        assertFalse(notification.body.has("id"))
        for (later in server.received.drop(2)) {
            assertEquals("session-1", later.headers["mcp-session-id"])
            assertEquals("2025-11-25", later.headers["mcp-protocol-version"])
            assertNull(later.headers["mcp-method"])
        }
        assertFalse(server.received[4].body.getJSONObject("params").has("_meta"))
        assertEquals("2", server.received[4].body.getJSONObject("params").getString("cursor"))

        val result = client.callTool(McpTool("t0", "", "{}"), JSONObject().put("a", 1))
        assertEquals("""t0:{"a":1}""", McpResults.flatten(result, 100))
        assertEquals("tools/call", server.methods().last())
        assertEquals(7, server.received.size)
    }

    @Test fun readsSseResponsesWithoutWaitingForTheStreamToClose() = runBlocking {
        for (modern in listOf(true, false)) {
            val server = server(modern, sse = true)
            server.tool("a")
            server.holdStreamOpenMs = 3000
            val started = System.nanoTime()
            val tools = client(server).listTools()
            assertEquals(listOf("a"), tools.map { it.name })
            assertTrue("did not wait for the stream to end", (System.nanoTime() - started) / 1_000_000 < 2500)
        }
    }

    @Test fun expiredSessionIsRenewed() = runBlocking {
        val server = server(modern = false)
        server.tool("a")
        val client = client(server)
        client.listTools()
        server.sessions.clear()
        assertEquals(1, client.listTools().size)
        assertEquals("session-1", server.received.last().headers["mcp-session-id"])
        assertEquals(listOf("tools/list", "initialize", "notifications/initialized", "tools/list"), server.methods().takeLast(4))
    }

    @Test fun olderNegotiatedRevisionIsUsedAndUnknownOnesAreRefused() {
        val old = server(modern = false, version = "2025-03-26")
        old.tool("a")
        val client = client(old)
        runBlocking { client.listTools() }
        assertEquals("2025-03-26", client.protocolVersion)
        assertEquals("2025-03-26", old.received.last().headers["mcp-protocol-version"])

        val alien = server(modern = false, version = "1999-01-01")
        assertTrue(failure { client(alien).listTools() }.message!!.contains("1999-01-01"))
    }

    @Test fun sendsAuthHeaderAndKeepsTheSecretOutOfErrors() {
        val bearer = server(modern = true, auth = "Authorization" to "Bearer s3cret-token")
        bearer.tool("a")
        runBlocking { assertEquals(1, client(bearer, headerValue = "s3cret-token").listTools().size) }

        val custom = server(modern = false, auth = "X-Api-Key" to "s3cret-token")
        custom.tool("a")
        runBlocking { assertEquals(1, client(custom, headerName = "X-Api-Key", headerValue = " s3cret-token ").listTools().size) }

        val denied = failure { client(bearer, headerValue = "wrong-secret").listTools() }
        assertTrue(denied.message, denied.message!!.contains("401"))
        assertFalse(denied.message!!.contains("wrong-secret"))
        // No handshake attempt after a refusal: it would only repeat the failure.
        assertEquals("tools/list", bearer.methods().last())

        val config = McpServerConfig("id", "n", "https://example.com/mcp?key=in-url", "X-Key", "s3cret-token")
        assertFalse(config.toString().contains("s3cret-token"))
        assertFalse(config.toString().contains("in-url"))
    }

    @Test fun reportsRpcErrorsReadablyAndRedactsAnEchoedSecret() {
        for (modern in listOf(true, false)) {
            val server = server(modern)
            server.call = { name, _ -> throw RpcFailure(-32602, "Unknown tool: $name (token s3cret-token)") }
            // Called before anything else, so the error also has to survive the detection of the server's era.
            val error = failure { client(server, headerValue = "s3cret-token").callTool(McpTool("nope", "", "{}"), JSONObject()) }
            assertEquals("Unknown tool: nope (token ***) (code -32602)", error.message)
        }
    }

    @Test fun failedToolCallsAreResultsNotExceptions() = runBlocking {
        val server = server(modern = false)
        server.call = { _, _ ->
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "boom"))).put("isError", true)
        }
        assertEquals("Error: boom", McpResults.flatten(client(server).callTool(McpTool("x", "", "{}"), JSONObject()), 100))
    }

    @Test fun modernErrorsDoNotTriggerTheHandshake() {
        val server = server(modern = true)
        server.tool("a")
        server.call = { _, _ -> JSONObject().put("resultType", "input_required").put("inputRequests", JSONObject()) }
        val client = client(server)
        val tools = runBlocking { client.listTools() }
        assertTrue(failure { client.callTool(tools[0], JSONObject()) }.message!!.contains("additional input"))
        assertFalse("initialize" in server.methods())
    }

    @Test fun mirrorsAnnotatedArgumentsIntoHeadersAndDropsInvalidTools() = runBlocking {
        val server = server(modern = true)
        server.tool(
            "sql",
            """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},
               "opts":{"type":"object","properties":{"limit":{"type":"integer","x-mcp-header":"Limit"},"dry":{"type":"boolean","x-mcp-header":"Dry"}}},
               "query":{"type":"string"}}}""",
        )
        server.tool("bad", """{"type":"object","properties":{"list":{"type":"array","items":{"type":"string","x-mcp-header":"Item"}}}}""")
        val client = client(server)
        val tools = client.listTools()
        assertEquals(listOf("sql"), tools.map { it.name })

        client.callTool(tools[0], JSONObject("""{"region":"Привет","opts":{"limit":42,"dry":null},"query":"select 1"}"""))
        val headers = server.received.last().headers
        assertEquals("=?base64?" + Base64.getEncoder().encodeToString("Привет".toByteArray()) + "?=", headers["mcp-param-region"])
        assertEquals("42", headers["mcp-param-limit"])
        assertNull(headers["mcp-param-dry"])
    }

    @Test fun unreachableAndBrokenServersGiveReadableErrors() {
        val server = server(modern = true)
        val url = server.url
        server.stop()
        val down = failure { McpClient(McpServerConfig("s", "s", url), connectTimeoutMs = 1000).listTools() }
        assertTrue(down.message, down.message!!.startsWith("Cannot reach 127.0.0.1"))

        assertTrue(failure { McpClient(McpServerConfig("s", "s", "ftp://example.com")).listTools() }.message!!.contains("https://"))
        assertTrue(failure { McpClient(McpServerConfig("s", "s", "not a url")).listTools() }.message!!.isNotEmpty())
        val badHeader = server(modern = true)
        val invalid = failure { client(badHeader, "Bad Header\n", "v").listTools() }
        assertTrue(invalid.message, invalid.message!!.contains("not valid"))
    }

    @Test fun cancellingTheCallerAbortsABlockedRequest() = runBlocking {
        val server = server(modern = true)
        server.tool("a")
        server.hangMs = 4000
        val started = System.nanoTime()
        val job = async(Dispatchers.Default) { client(server, readTimeoutMs = 30_000).listTools() }
        delay(300)
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
        }
    }

    @Test fun adaptsToolsForTheChatModel() = runBlocking {
        val server = server(modern = false)
        server.tool("get.weather")
        server.tool("get_weather")
        server.tool("get weather")
        server.tools[0].put("description", "word ".repeat(200))
        server.call = { _, _ ->
            JSONObject().put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "x".repeat(500)))
                    .put(JSONObject().put("type", "image").put("data", "AAAA").put("mimeType", "image/png")),
            )
        }
        val client = client(server)
        val tools = mcpTools("My Server!", client, client.listTools(), maxResultChars = 100)
        assertEquals(listOf("my_server_get_weather", "my_server_get_weather_2", "my_server_get_weather_3"), tools.map { it.spec.name })
        assertTrue(tools[0].spec.description.length <= 300 && tools[0].spec.description.endsWith("…"))
        assertEquals("Tool get_weather", tools[1].spec.description)
        JSONObject(tools[0].spec.parametersSchema)

        val log = AgentLog()
        val text = tools[0].run(JSONObject().put("q", "paris"), log)
        assertTrue(text.startsWith("x".repeat(100) + "\n[cut"))
        assertTrue(text.length < 200)
        assertEquals("get.weather", server.received.last().body.getJSONObject("params").getString("name"))
        assertEquals("paris", server.received.last().body.getJSONObject("params").getJSONObject("arguments").getString("q"))
        assertEquals(1, log.entries.value.size)
        assertEquals(2, mcpTools("s", client, client.listTools(), limit = 2).size)
    }
}
