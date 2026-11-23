package com.modulai.core.code

import com.modulai.core.agent.Agent
import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.Tool
import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.StopReason
import com.modulai.core.runtime.TextSession
import com.modulai.core.runtime.ToolSpec
import com.modulai.core.runtime.parseReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The system prompt of the coder role: short rules a small model can hold on to, and the files
 * that exist right now. The tree takes at most [maxTreeChars], so a big project cannot crowd out the task.
 */
fun coderSystemPrompt(files: List<FileEntry>, maxTreeChars: Int = 1500): String {
    val tree = StringBuilder()
    var listed = 0
    for (file in files) {
        if (tree.length + file.path.length + 1 > maxTreeChars) break
        tree.append(file.path).append('\n')
        listed++
    }
    val rest = files.size - listed
    val project = when {
        files.isEmpty() -> "The project is empty."
        rest > 0 -> "Project files:\n$tree… and $rest more. Call list_files to see all."
        else -> "Project files:\n${tree.trimEnd()}"
    }
    return """
        |You are a programmer working on the user's project. You change the project only through tools.
        |Rules:
        |- To create or change a file call write_file or edit_file. Code written in the chat is not saved.
        |- Before changing an existing file, read it with read_file.
        |- write_file takes the complete file, from the first line to the last. Never shorten it with "..." or "the rest is unchanged".
        |- For a small change in a long file use edit_file with a fragment copied exactly from the file.
        |- File paths are relative to the project, like index.html or src/app.js.
        |- A web page must start from index.html; link its CSS and JS files with relative paths.
        |- When the work is done, say in one or two sentences what you changed. Do not repeat the code.
        |- Answer in the language of the user.
        |
        |$project
    """.trimMargin()
}

data class CoderResult(
    /** Everything produced in this run, in order: assistant turns and tool results. */
    val messages: List<ChatMessage>,
    val stop: StopReason,
    /** Messages dropped from the prompt to make it fit. */
    val trimmed: Int,
    /** What the run did to the project, file by file. */
    val changes: List<FileChange>,
)

/** Runs the coder role on a project: the model works through the file tools until it answers. */
class Coder(
    private val session: TextSession,
    private val workspace: Workspace,
    private val log: AgentLog,
    private val maxSteps: Int = 16,
) {
    /**
     * [history] is the conversation without a system prompt, ending with the task.
     * [onChange] runs after every file change, on a background thread.
     * [onText] receives the assistant turn being generated, as in [Agent.run].
     */
    suspend fun run(
        projectId: String,
        history: List<ChatMessage>,
        params: SamplingParams,
        onChange: () -> Unit = {},
        onText: (String) -> Unit = {},
    ): CoderResult {
        val project = workspace.open(projectId)
        val changes = workspace.changes(projectId)
        val files = withContext(Dispatchers.IO) {
            changes.begin()
            project.files()
        }
        val system = coderSystemPrompt(files, maxTreeChars = (session.contextTokens / 4).coerceIn(400, 2000))
        val tools = codeTools(project, changes, maxReadChars = session.contextTokens.coerceIn(1500, 12000), onChange)
        val result = Agent(session, tools, log, maxSteps).run(listOf(ChatMessage(Role.SYSTEM, system)) + history, params, onText)
        return CoderResult(result.messages, result.stop, result.trimmed, withContext(Dispatchers.IO) { changes.changes(project) })
    }
}

/** One line per changed file, for a model or a log. */
fun describeChanges(changes: List<FileChange>): String = changes.joinToString("\n") { change ->
    when (change.kind) {
        ChangeKind.CREATED -> "+ ${change.path} (created, ${splitLines(change.after).size} lines)"
        ChangeKind.DELETED -> "- ${change.path} (deleted)"
        ChangeKind.MODIFIED -> diffLines(change.before, change.after, context = 0).stats().let {
            "~ ${change.path} (changed, +${it.added} -${it.removed} lines)"
        }
    }
}

/**
 * Lets another model hand programming work to the coder. The coder runs on [session] with the
 * file tools and its own system prompt; only a summary of what it did comes back.
 *
 * [onProject] is called with the project the task ran on once the run ends, so a screen showing it can refresh.
 */
class CodeTaskTool(
    private val workspace: Workspace,
    private val session: suspend () -> TextSession,
    private val params: () -> SamplingParams,
    private val onProject: (Project) -> Unit = {},
    private val maxSteps: Int = 16,
) : Tool {
    override val spec = ToolSpec(
        "code",
        "Hand a programming task to the coder: it writes and edits the files of a project and returns the list of " +
            "changed files. Use it when the user asks to write a program, a script or a web page, or to change one. " +
            "Slow (minutes).",
        """{"type":"object","properties":{"task":{"type":"string","description":"What to build or change, in full detail"},"project":{"type":"string","description":"Name of the project to continue. Leave out to start a new project"}},"required":["task"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val task = args.optString("task").trim()
        if (task.isEmpty()) return "Error: empty task"
        val wanted = args.optString("project").trim()
        val project = withContext(Dispatchers.IO) {
            wanted.takeIf { it.isNotEmpty() }?.let { workspace.findByName(it) }
                ?: workspace.create(wanted.ifEmpty { task.lineSequence().first().take(40) })
        }
        try {
            val result = Coder(session(), workspace, log, maxSteps)
                .run(project.id, listOf(ChatMessage(Role.USER, task)), params())
            val answer = parseReply(result.messages.lastOrNull { it.role == Role.ASSISTANT }?.content.orEmpty()).answer
            val files = if (result.changes.isEmpty()) "No files were changed." else "Changed files:\n${describeChanges(result.changes)}"
            return "Project \"${project.name}\". $files\n\nCoder: ${answer.take(MAX_ANSWER)}"
        } finally {
            onProject(project)
        }
    }

    private companion object {
        const val MAX_ANSWER = 800
    }
}
