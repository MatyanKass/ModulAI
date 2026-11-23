package com.modulai.core.code

import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.Tool
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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Replies are scripted: each generate() call emits the next list of events. */
private class ScriptedSession(private val script: List<List<TextEvent>>) : TextSession {
    val prompts = ArrayList<List<ChatMessage>>()
    override val model get() = error("unused")
    override val backend = Backend.CPU
    override val contextTokens = 4096
    override suspend fun countTokens(messages: List<ChatMessage>, effort: Effort, tools: List<ToolSpec>) = 10
    override fun generate(messages: List<ChatMessage>, params: SamplingParams, tools: List<ToolSpec>): Flow<TextEvent> = flow {
        prompts += messages
        script[prompts.size - 1].forEach { emit(it) }
        emit(TextEvent.Done(StopReason.END))
    }
    override fun close() = Unit
}

private fun call(name: String, vararg args: Pair<String, Any>) =
    listOf<TextEvent>(TextEvent.ToolCall(name, JSONObject(args.toMap()).toString()))

class CodeToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var workspace: Workspace
    private lateinit var project: Project
    private lateinit var dir: ProjectDir
    private lateinit var changes: ChangeLog
    private lateinit var tools: List<Tool>
    private val log = AgentLog()
    private var changed = 0

    @Before fun setUp() {
        workspace = Workspace(File(tmp.root, "projects"))
        project = workspace.create("demo")
        dir = workspace.open(project.id)
        changes = workspace.changes(project.id)
        tools = codeTools(dir, changes, maxReadChars = 200) { changed++ }
    }

    private fun run(name: String, vararg args: Pair<String, Any>): String = runBlocking {
        tools.first { it.spec.name == name }.run(JSONObject(args.toMap()), log)
    }

    private fun fails(name: String, vararg args: Pair<String, Any>): String = try {
        run(name, *args)
        fail("Expected $name to fail")
        ""
    } catch (e: Exception) {
        e.message.orEmpty()
    }

    @Test fun schemasAreValidJsonAndNamesAreAsDocumented() {
        assertEquals(
            listOf("list_files", "read_file", "write_file", "edit_file", "delete_file", "search_files"),
            tools.map { it.spec.name },
        )
        tools.forEach { assertEquals("object", JSONObject(it.spec.parametersSchema).getString("type")) }
    }

    @Test fun writeCreatesThenReplacesAndReportsBoth() {
        assertEquals("Created src/app.js (2 lines).", run("write_file", "path" to "src/app.js", "content" to "a\nb\n"))
        assertEquals("Replaced src/app.js: 1 lines now, 2 before.", run("write_file", "path" to "./src/app.js", "content" to "c"))
        assertEquals("c", dir.read("src/app.js"))
        assertEquals(2, changed)
        assertEquals(LogKind.TOOL_RESULT, log.entries.value.last().kind)
    }

    @Test fun argumentNamesAreForgiving() {
        run("write_file", "file_path" to "a.py", "code" to "```python\nprint(1)\n```")
        assertEquals("the Markdown fence is not part of the file", "print(1)\n", dir.read("a.py"))
        run("edit_file", "file" to "a.py", "old_string" to "print(1)", "new_string" to "print(2)")
        assertEquals("print(2)\n", dir.read("a.py"))
        run("write_file", "path" to "notes.md", "content" to "```\ncode\n```")
        assertEquals("```\ncode\n```", dir.read("notes.md"))
        assertTrue(fails("write_file", "content" to "x").contains("path"))
        assertTrue(fails("write_file", "path" to "x.txt").contains("content"))
    }

    @Test fun listAndSearch() {
        assertTrue(run("list_files").contains("empty"))
        dir.write("index.html", "<h1>Hello</h1>\n<p>hello again</p>")
        dir.write("js/app.js", "const x = 42\n")
        dir.writeBytes("logo.png", byteArrayOf(0, 1))
        assertEquals("3 files:\nindex.html (33 B)\njs/app.js (13 B)\nlogo.png (2 B)", run("list_files"))

        assertEquals(
            "2 matches for \"hello\":\nindex.html:1: <h1>Hello</h1>\nindex.html:2: <p>hello again</p>",
            run("search_files", "query" to "hello"),
        )
        assertEquals("1 matches for \"x = \\d+\":\njs/app.js:1: const x = 42", run("search_files", "query" to "x = \\d+", "regex" to true))
        assertEquals("No matches for \"(\".", run("search_files", "query" to "(", "regex" to true))
        assertEquals("No matches for \"nothing\".", run("search_files", "query" to "nothing"))
    }

    @Test fun readReturnsRangesAndCutsLongFiles() {
        dir.write("big.txt", (1..100).joinToString("\n") { "line number $it" })
        val head = run("read_file", "path" to "big.txt")
        assertTrue(head, head.startsWith("big.txt, lines 1-13 of 100:\nline number 1\n"))
        assertTrue(head, head.endsWith("line number 13\n[Cut here. Call read_file with start_line=14 to read on.]"))

        assertEquals("big.txt, lines 40-41 of 100:\nline number 40\nline number 41", run("read_file", "path" to "big.txt", "start_line" to 40, "end_line" to "41"))
        assertEquals("big.txt, lines 100-100 of 100:\nline number 100", run("read_file", "path" to "big.txt", "start_line" to 500))

        dir.write("small.txt", "a\nb\n")
        assertEquals("small.txt (2 lines):\na\nb", run("read_file", "path" to "small.txt"))
        dir.write("wide.txt", "x".repeat(1000))
        assertEquals(200, run("read_file", "path" to "wide.txt").substringAfter("\n").length)
        assertTrue(fails("read_file", "path" to "missing.txt").contains("does not exist"))
        assertTrue(fails("read_file", "path" to "../project.json").contains("leaves the project"))
    }

    @Test fun editReplacesExactlyOneFragment() {
        dir.write("a.js", "function a() {\n  return 1\n}\nfunction b() {\n  return 1\n}\n")
        assertEquals(
            "Edited a.js at line 4: 2 lines replaced with 3.",
            run("edit_file", "path" to "a.js", "old_text" to "function b() {\n  return 1", "new_text" to "function b() {\n  const x = 2\n  return x"),
        )
        assertEquals("function a() {\n  return 1\n}\nfunction b() {\n  const x = 2\n  return x\n}\n", dir.read("a.js"))
    }

    @Test fun editExplainsWhyNothingMatched() {
        val source = "function a() {\n    return 1\n}\nfunction b() {\n    return 1\n}\n"
        dir.write("a.js", source)

        val several = fails("edit_file", "path" to "a.js", "old_text" to "return 1", "new_text" to "return 2")
        assertTrue(several, several.contains("occurs 2 times") && several.contains("lines 2, 5"))

        val whitespace = fails("edit_file", "path" to "a.js", "old_text" to "function b() {\n  return 1\n}", "new_text" to "x")
        assertTrue(whitespace, whitespace.contains("near line 4") && whitespace.contains("indentation"))

        val absent = fails("edit_file", "path" to "a.js", "old_text" to "function c()", "new_text" to "x")
        assertTrue(absent, absent.contains("has no such text"))

        val partial = fails("edit_file", "path" to "a.js", "old_text" to "function b() {\n  return 7\n}", "new_text" to "x")
        assertTrue(partial, partial.contains("Only its beginning matches"))

        assertTrue(fails("edit_file", "path" to "a.js", "new_text" to "x").contains("old_text"))
        assertEquals(source, dir.read("a.js"))
        assertTrue("failed edits record nothing", changes.changes(dir).isEmpty())
        assertEquals(0, changed)
    }

    @Test fun changesOfARunCanBeListedAndReverted() {
        dir.write("keep.txt", "one\ntwo\n")
        dir.write("gone.txt", "bye")
        dir.write("same.txt", "same")
        changes.begin()

        run("write_file", "path" to "new.txt", "content" to "fresh")
        run("edit_file", "path" to "keep.txt", "old_text" to "two", "new_text" to "2")
        run("edit_file", "path" to "keep.txt", "old_text" to "one", "new_text" to "1")
        run("delete_file", "path" to "gone.txt")
        run("write_file", "path" to "same.txt", "content" to "same")

        val listed = changes.changes(dir)
        assertEquals(
            listOf("gone.txt" to ChangeKind.DELETED, "keep.txt" to ChangeKind.MODIFIED, "new.txt" to ChangeKind.CREATED),
            listed.map { it.path to it.kind },
        )
        assertEquals("the original is the state before the run, not before the last edit", "one\ntwo\n", listed[1].before)
        assertEquals("1\n2\n", listed[1].after)
        assertEquals(
            "- gone.txt (deleted)\n~ keep.txt (changed, +2 -2 lines)\n+ new.txt (created, 1 lines)",
            describeChanges(listed),
        )

        // A fresh instance reads the same run back from disk.
        assertEquals(listed, ChangeLog(workspace.sidecar(project.id, "changes")).changes(dir))

        changes.revert(dir, "keep.txt")
        changes.revert(dir, "new.txt")
        changes.revert(dir, "gone.txt")
        assertEquals("one\ntwo\n", dir.read("keep.txt"))
        assertFalse(dir.exists("new.txt"))
        assertEquals("bye", dir.read("gone.txt"))
        assertTrue(changes.changes(dir).isEmpty())
        refused(Refusal.NOT_FOUND) { changes.revert(dir, "same2.txt") }

        changes.begin()
        assertTrue("a new run forgets the previous one", changes.changes(dir).isEmpty())
    }

    @Test fun systemPromptListsFilesWithinItsBudget() {
        assertTrue(coderSystemPrompt(emptyList()).endsWith("The project is empty."))
        val few = coderSystemPrompt(listOf(FileEntry("index.html", 1), FileEntry("js/app.js", 2)))
        assertTrue(few, few.endsWith("Project files:\nindex.html\njs/app.js"))

        val many = (1..500).map { FileEntry("src/file$it.js", 1) }
        val rules = coderSystemPrompt(emptyList()).length
        val prompt = coderSystemPrompt(many, maxTreeChars = 300)
        assertTrue(prompt.length < rules + 300 + 80)
        assertTrue(prompt, Regex("… and 4\\d\\d more\\. Call list_files to see all\\.$").containsMatchIn(prompt))
    }

    @Test fun coderRunsToolsAgainstTheProject() = runBlocking {
        dir.write("old.txt", "old")
        val session = ScriptedSession(
            listOf(
                call("write_file", "path" to "index.html", "content" to "<h1>Hi</h1>"),
                call("write_file", "path" to "../../escape.txt", "content" to "x"),
                listOf(TextEvent.Piece("Done.")),
            ),
        )
        var notified = 0
        val result = Coder(session, workspace, log).run(project.id, listOf(ChatMessage(Role.USER, "make a page")), SamplingParams(), { notified++ })

        assertEquals(Role.SYSTEM, session.prompts[0][0].role)
        assertTrue(session.prompts[0][0].content.contains("Project files:\nold.txt"))
        assertEquals(listOf("index.html"), result.changes.map { it.path })
        assertEquals(1, notified)
        val toolResults = result.messages.filter { it.role == Role.TOOL }.map { it.content }
        assertEquals("Created index.html (1 lines).", toolResults[0])
        assertTrue(toolResults[1], toolResults[1].startsWith("Error:") && toolResults[1].contains("leaves the project"))
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertEquals("Done.", result.messages.last().content)
    }

    @Test fun codeTaskToolCreatesAProjectAndSummarizes() = runBlocking {
        val seen = ArrayList<Project>()
        fun tool(session: ScriptedSession) = CodeTaskTool(workspace, { session }, { SamplingParams() }, { seen += it })

        val first = ScriptedSession(
            listOf(
                call("write_file", "path" to "main.py", "content" to "print('hi')\n"),
                listOf(TextEvent.Piece("<think>plan</think>Wrote the script.")),
            ),
        )
        val created = tool(first).run(JSONObject().put("task", "Write a hello script\nin Python"), log)
        assertEquals(
            "Project \"Write a hello script\". Changed files:\n+ main.py (created, 1 lines)\n\nCoder: Wrote the script.",
            created,
        )
        assertEquals("Write a hello script\nin Python", first.prompts[0].last().content)

        val second = ScriptedSession(
            listOf(
                call("edit_file", "path" to "main.py", "old_text" to "hi", "new_text" to "hello"),
                listOf(TextEvent.Piece("Changed the greeting.")),
            ),
        )
        val edited = tool(second).run(JSONObject().put("task", "Say hello").put("project", "write a hello script"), log)
        assertTrue(edited, edited.contains("~ main.py (changed, +1 -1 lines)"))
        assertEquals(2, workspace.projects().size)
        assertEquals("print('hello')\n", workspace.open(seen[1].id).read("main.py"))
        assertEquals(seen[0].id, seen[1].id)

        assertEquals("Error: empty task", tool(ScriptedSession(emptyList())).run(JSONObject(), log))
        val idle = tool(ScriptedSession(listOf(listOf(TextEvent.Piece("Nothing to do.")))))
            .run(JSONObject().put("task", "Look around").put("project", "Fresh"), log)
        assertEquals("Project \"Fresh\". No files were changed.\n\nCoder: Nothing to do.", idle)
    }
}
