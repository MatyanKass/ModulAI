package com.modulai.core.agent

import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Effort
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.StopReason
import com.modulai.core.runtime.TextEvent
import com.modulai.core.runtime.TextSession
import com.modulai.core.runtime.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Replies are scripted: each generate() call consumes the next lambda. */
private class ScriptedSession(private val script: List<(List<ChatMessage>, List<ToolSpec>) -> List<TextEvent>>) : TextSession {
    val prompts = ArrayList<List<ChatMessage>>()
    override val model get() = error("unused")
    override val backend = Backend.CPU
    override val contextTokens = 4096
    override suspend fun countTokens(messages: List<ChatMessage>, effort: Effort, tools: List<ToolSpec>) = 10
    override fun generate(messages: List<ChatMessage>, params: SamplingParams, tools: List<ToolSpec>): Flow<TextEvent> = flow {
        prompts += messages
        script[prompts.size - 1](messages, tools).forEach { emit(it) }
        emit(TextEvent.Done(StopReason.END))
    }
    override fun close() = Unit
}

private fun say(text: String) = { _: List<ChatMessage>, _: List<ToolSpec> -> listOf<TextEvent>(TextEvent.Piece(text)) }

private class FakeWeb(private val failing: Set<String> = emptySet()) : Web {
    val searches = ArrayList<String>()
    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        searches += query
        return listOf(
            SearchResult("Shared", "https://a.example/shared", "s"),
            SearchResult("About $query", "https://a.example/${query.hashCode()}", "s"),
        )
    }
    override suspend fun fetch(url: String, maxChars: Int): Page {
        if (url in failing) throw IOException("HTTP 500")
        return Page(url, "Title of $url", "Body of $url", "Mon, 02 Nov 2026 10:00:00 GMT")
    }
}

class AgentTest {
    private val user = listOf(ChatMessage(Role.USER, "weather?"))

    @Test fun answersDirectlyWithoutTools() = runTest {
        val session = ScriptedSession(listOf(say("hello")))
        val result = Agent(session, listOf(WebSearchTool(FakeWeb())), AgentLog()).run(user, SamplingParams()) {}
        assertEquals(listOf(ChatMessage(Role.ASSISTANT, "hello")), result.messages)
    }

    @Test fun runsToolAndFeedsResultBack() = runTest {
        val web = FakeWeb()
        val session = ScriptedSession(
            listOf(
                { _, _ -> listOf(TextEvent.Piece("<tool_call>…"), TextEvent.ToolCall("web_search", """{"query":"paris weather"}""")) },
                say("It rains."),
            ),
        )
        val log = AgentLog()
        val result = Agent(session, listOf(WebSearchTool(web)), log).run(user, SamplingParams()) {}

        assertEquals(listOf("paris weather"), web.searches)
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), result.messages.map { it.role })
        assertTrue(result.messages[1].content.contains("https://a.example/shared"))
        // The second prompt must contain the tool result.
        assertEquals(Role.TOOL, session.prompts[1].last().role)
        assertTrue(log.entries.value.any { it.kind == LogKind.SEARCH && it.title == "paris weather" })
    }

    @Test fun toolFailureIsReportedToTheModel() = runTest {
        val session = ScriptedSession(
            listOf(
                { _, _ -> listOf(TextEvent.ToolCall("fetch_page", """{"url":"https://bad.example"}""")) },
                say("Could not open it."),
            ),
        )
        val web = FakeWeb(failing = setOf("https://bad.example"))
        val result = Agent(session, listOf(FetchPageTool(web)), AgentLog()).run(user, SamplingParams()) {}
        assertEquals("Error: HTTP 500", result.messages[1].content)
    }

    @Test fun unknownToolAndBadJsonDoNotCrash() = runTest {
        val session = ScriptedSession(
            listOf(
                { _, _ -> listOf(TextEvent.ToolCall("nope", "{}"), TextEvent.ToolCall("web_search", "not json")) },
                say("done"),
            ),
        )
        val result = Agent(session, listOf(WebSearchTool(FakeWeb())), AgentLog()).run(user, SamplingParams()) {}
        assertEquals("Error: unknown tool nope", result.messages[1].content)
        assertEquals("Error: empty query", result.messages[2].content)
    }

    @Test fun lastStepWithholdsTools() = runTest {
        val offered = ArrayList<Int>()
        val loop = { _: List<ChatMessage>, tools: List<ToolSpec> ->
            offered += tools.size
            if (tools.isEmpty()) listOf<TextEvent>(TextEvent.Piece("final"))
            else listOf<TextEvent>(TextEvent.ToolCall("web_search", """{"query":"q"}"""))
        }
        val session = ScriptedSession(List(3) { loop })
        val result = Agent(session, listOf(WebSearchTool(FakeWeb())), AgentLog(), maxSteps = 3).run(user, SamplingParams()) {}
        assertEquals(listOf(1, 1, 0), offered)
        assertEquals("final", result.messages.last().content)
    }

    @Test fun researchReadsPagesAndCitesSources() = runTest {
        val web = FakeWeb(failing = setOf("https://a.example/shared"))
        val session = ScriptedSession(
            listOf(
                say("first query\n2. second query"),
                say("- fact one"),
                say("NONE"),
                say("# Report\nFact one [1]."),
            ),
        )
        val seen = ArrayList<ResearchProgress>()
        val report = Researcher(session, web, AgentLog()).run("topic", ResearchDepth.QUICK, SamplingParams()) { seen += it }

        assertEquals(listOf("first query", "second query"), web.searches)
        // Three unique pages: one fails to load, one is irrelevant, one yields a note.
        assertEquals(1, report.sources.size)
        assertEquals(1, report.sources[0].index)
        assertEquals("# Report\nFact one [1].", report.text)
        assertTrue(session.prompts.last().single().content.contains("[1] Title of"))
        assertTrue(seen.any { it is ResearchProgress.Writing })
    }
}
