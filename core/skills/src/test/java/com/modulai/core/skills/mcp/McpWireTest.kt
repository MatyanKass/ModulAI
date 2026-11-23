package com.modulai.core.skills.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class McpWireTest {
    private fun events(stream: String): List<String> {
        val seen = ArrayList<String>()
        Sse.first<String>(stream.reader().buffered(), 10_000) { data ->
            seen += data
            null
        }
        return seen
    }

    @Test fun parsesSseEvents() {
        assertEquals(
            listOf("", "one", "two\nlines", "no-space", "unterminated"),
            events(": comment\n\ndata:\n\nevent: message\nid: 7\ndata: one\n\ndata: two\ndata: lines\n\nretry: 100\n\ndata:no-space\r\n\r\ndata: unterminated"),
        )
        assertTrue(events("").isEmpty())
        assertEquals("b", Sse.first("data: a\n\ndata: b\n\ndata: c\n\n".reader().buffered(), 100) { it.takeIf { d -> d == "b" } })
        assertThrows(IOException::class.java) { events("data: ${"x".repeat(20_000)}\n\n") }
        assertThrows(IOException::class.java) {
            Sse.first<String>("data: a\n\n".reader().buffered(), 100, deadlineNanos = System.nanoTime() - 1) { null }
        }
    }

    @Test fun encodesHeaderValuesAsTheSpecificationShows() {
        assertEquals("us-west1", McpHeaders.encode("us-west1"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", McpHeaders.encode("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpHeaders.encode(" padded "))
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", McpHeaders.encode("line1\nline2"))
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", McpHeaders.encode("=?base64?literal?="))
    }

    @Test fun findsAndValidatesHeaderAnnotations() {
        val schema = JSONObject(
            """{"type":"object","properties":{
                "region":{"type":"string","x-mcp-header":"Region"},
                "x-mcp-header":{"type":"string"},
                "nested":{"type":"object","properties":{"n":{"type":"integer","x-mcp-header":"N"}}},
                "plain":{"type":"number"}}}""",
        )
        assertEquals(listOf(HeaderParam(listOf("region"), "Region"), HeaderParam(listOf("nested", "n"), "N")), McpHeaders.params(schema).sortedBy { it.path.size })
        assertTrue(McpHeaders.params(JSONObject("""{"type":"object"}""")).isEmpty())

        val invalid = listOf(
            """{"properties":{"a":{"type":"number","x-mcp-header":"A"}}}""",
            """{"properties":{"a":{"type":"string","x-mcp-header":""}}}""",
            """{"properties":{"a":{"type":"string","x-mcp-header":"Bad Name"}}}""",
            """{"properties":{"a":{"type":"string","x-mcp-header":7}}}""",
            """{"properties":{"a":{"type":"string","x-mcp-header":"X"},"b":{"type":"string","x-mcp-header":"x"}}}""",
            """{"properties":{"a":{"type":"array","items":{"type":"string","x-mcp-header":"X"}}}}""",
            """{"properties":{"a":{"oneOf":[{"type":"string","x-mcp-header":"X"}]}}}""",
            """{"allOf":[{"properties":{"a":{"type":"string","x-mcp-header":"X"}}}]}""",
            """{"type":"string","x-mcp-header":"Root"}""",
        )
        for (schema in invalid) assertThrows(schema, IllegalArgumentException::class.java) { McpHeaders.params(JSONObject(schema)) }
    }

    @Test fun readsHeaderValuesFromArguments() {
        val args = JSONObject("""{"s":"text","i":42,"big":9007199254740991,"f":3.0,"frac":1.5,"b":false,"nil":null,"o":{"k":"v"}}""")
        assertEquals("text", McpHeaders.valueAt(args, listOf("s")))
        assertEquals("42", McpHeaders.valueAt(args, listOf("i")))
        assertEquals("9007199254740991", McpHeaders.valueAt(args, listOf("big")))
        assertEquals("3", McpHeaders.valueAt(args, listOf("f")))
        assertEquals("false", McpHeaders.valueAt(args, listOf("b")))
        assertEquals("v", McpHeaders.valueAt(args, listOf("o", "k")))
        for (path in listOf(listOf("frac"), listOf("nil"), listOf("o"), listOf("missing"), listOf("s", "deeper"))) {
            assertNull(path.toString(), McpHeaders.valueAt(args, path))
        }
    }

    @Test fun flattensToolResults() {
        val result = JSONObject(
            """{"content":[
                {"type":"text","text":"first"},
                {"type":"image","data":"AAAA","mimeType":"image/png"},
                {"type":"audio","data":"AAAA","mimeType":"audio/wav"},
                {"type":"resource_link","uri":"file:///a.rs","name":"a.rs"},
                {"type":"resource","resource":{"uri":"file:///b.rs","text":"fn main() {}"}},
                {"type":"resource","resource":{"uri":"file:///c.bin","blob":"AAAA"}},
                {"type":"text","text":"last"}],
               "structuredContent":{"ignored":true}}""",
        )
        assertEquals(
            "first\n[image]\n[audio]\n[resource: file:///a.rs]\nfn main() {}\n[resource: file:///c.bin]\nlast",
            McpResults.flatten(result, 1000),
        )
        assertEquals("""{"temp":21}""", McpResults.flatten(JSONObject("""{"content":[],"structuredContent":{"temp":21}}"""), 100))
        assertEquals("(empty result)", McpResults.flatten(JSONObject("{}"), 100))
        assertEquals("Error: (empty result)", McpResults.flatten(JSONObject("""{"isError":true,"content":[{"type":"text","text":null}]}"""), 100))
        val long = McpResults.flatten(JSONObject().put("isError", true).put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "y".repeat(5000)))), 50)
        assertTrue(long.startsWith("Error: " + "y".repeat(50) + "\n[cut") && long.length < 120)
    }
}

class McpStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun persistsServersAndMakesIds() {
        val store = McpStore(tmp.root)
        assertTrue(store.list().isEmpty())
        val first = store.save(McpServerConfig("", "My Tools!", "https://a.example/mcp", headerValue = "secret"))
        val second = store.save(McpServerConfig("", "my tools", "https://b.example/mcp", "X-Key", "k", enabled = false))
        val unnamed = store.save(McpServerConfig("", "Сервер", "https://c.example/mcp"))
        assertEquals(listOf("my_tools", "my_tools_2", "server"), listOf(first.id, second.id, unnamed.id))

        store.save(first.copy(name = "Renamed", enabled = false))
        val reopened = McpStore(tmp.root).list()
        assertEquals(listOf(first.copy(name = "Renamed", enabled = false), second, unnamed), reopened)

        store.delete(second.id)
        assertEquals(listOf("my_tools", "server"), McpStore(tmp.root).list().map { it.id })
    }

    @Test fun buildsTheAuthHeader() {
        fun header(name: String, value: String) = McpServerConfig("i", "n", "u", name, value).authHeader()
        assertNull(header("", "  "))
        assertNull(header("X-Key", ""))
        assertEquals("Authorization" to "Bearer abc", header("", " abc "))
        assertEquals("Authorization" to "bearer abc", header("", "bearer abc"))
        assertEquals("X-Api-Key" to "abc", header(" X-Api-Key ", "abc"))
        assertEquals("Authorization" to "Basic abc", header("Authorization", "Basic abc"))
    }
}
