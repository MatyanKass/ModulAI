package com.modulai.app.code

import android.content.Context
import com.modulai.app.AppContainer
import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.Tool
import com.modulai.core.code.CodeTaskTool
import com.modulai.core.code.Coder
import com.modulai.core.code.FileChange
import com.modulai.core.code.FileEntry
import com.modulai.core.code.Project
import com.modulai.core.code.ProjectDir
import com.modulai.core.code.Refusal
import com.modulai.core.code.Workspace
import com.modulai.core.code.WorkspaceException
import com.modulai.core.code.exportZip
import com.modulai.core.code.importZip
import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.PromptTooLongException
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.StopReason
import com.modulai.core.runtime.TextSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

sealed interface CodeError {
    /** The workspace refused an operation; the UI has a message for every reason. */
    data class Refused(val refusal: Refusal) : CodeError
    data object PromptTooLong : CodeError
    data class Other(val message: String) : CodeError
}

data class CodeState(
    val projects: List<Project> = emptyList(),
    /** The project on screen; null shows the project list. */
    val project: Project? = null,
    val files: List<FileEntry> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    /** What the last run did to the project. */
    val changes: List<FileChange> = emptyList(),
    val running: Boolean = false,
    /** An import or export is in progress. */
    val busy: Boolean = false,
    /** Messages dropped from the prompt because the conversation outgrew the context. */
    val trimmed: Int = 0,
    val truncated: Boolean = false,
    val error: CodeError? = null,
    /** Grows whenever files of the open project change on disk. */
    val revision: Int = 0,
)

/** Text typed into the editor and not saved yet. */
data class Draft(val path: String, val saved: String, val text: String)

/**
 * The coding workspace: projects, the conversation with the coder in each, and its runs.
 * The coder is the model selected for chat. Projects live in [projectsDir], one directory each,
 * with the conversation stored next to the project's files.
 */
class CodeController(private val container: AppContainer, projectsDir: File) {
    val workspace = Workspace(projectsDir)

    private val _state = MutableStateFlow(CodeState())
    val state: StateFlow<CodeState> = _state

    /** What the coder did in the current run; shown in the "workspace" sheet. */
    val log = AgentLog()

    /** Kept here rather than in the screen, so unsaved text survives leaving the editor and a rotation. */
    var draft: Draft? = null

    private var job: Job? = null

    /** File operations run one at a time, in the order they were asked for. */
    private val ioLock = Mutex()

    fun refresh() = io { publishProjects() }

    fun create(name: String) = io {
        val project = workspace.create(name)
        publishProjects()
        show(project)
    }

    fun rename(id: String, name: String) = io {
        val renamed = workspace.rename(id, name)
        publishProjects()
        _state.update { if (it.project?.id == id) it.copy(project = renamed) else it }
    }

    fun delete(id: String) {
        if (_state.value.project?.id == id) close()
        io {
            workspace.delete(id)
            publishProjects()
        }
    }

    /** [open] is called on a background thread and may return null when the document cannot be read. */
    fun import(name: String, open: () -> InputStream?) = io(busy = true) {
        val input = open() ?: throw WorkspaceException(Refusal.BAD_ARCHIVE, "The archive cannot be opened")
        val project = input.use { workspace.importZip(name, it) }
        publishProjects()
        show(project)
    }

    fun export(id: String, open: () -> OutputStream?) = io(busy = true) {
        val output = open() ?: throw WorkspaceException(Refusal.NOT_FOUND, "The document cannot be written")
        output.use { workspace.open(id).exportZip(it) }
    }

    fun open(id: String) = io {
        workspace.find(id)?.let { show(it) }
    }

    /** Back to the project list. A run in progress is stopped: nothing would be watching it. */
    fun close() {
        stop()
        draft = null
        _state.update { CodeState(projects = it.projects) }
        refresh()
    }

    fun stop() {
        job?.cancel()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Forgets the conversation of the open project; its files stay. */
    fun newConversation() {
        val project = _state.value.project ?: return
        stop()
        log.clear()
        _state.update { it.copy(messages = emptyList(), trimmed = 0, truncated = false, error = null) }
        io { conversation(project.id).delete() }
    }

    /** The open project's files, for the preview. Paths given to it are confined to the project. */
    fun projectDir(): ProjectDir? = _state.value.project?.let { runCatching { workspace.open(it.id) }.getOrNull() }

    /** Fails with [WorkspaceException] when the file is missing or is not text. */
    suspend fun readText(path: String): String = withContext(Dispatchers.IO) { dir().read(path) }

    fun writeText(path: String, text: String) = change { it.write(path, text) }

    fun createFile(path: String) = change {
        if (it.exists(path)) throw WorkspaceException(Refusal.ALREADY_EXISTS, "\"$path\" already exists")
        it.write(path, "")
    }

    fun deleteFile(path: String) = change { it.delete(path) }

    fun moveFile(from: String, to: String) = change { it.move(from, to) }

    /** Puts a file back to what it was before the last run. */
    fun revert(path: String) = change { workspace.changes(it.id).revert(it, path) }

    fun send(text: String) {
        val project = _state.value.project ?: return
        if (_state.value.running || text.isBlank() || container.chat.state.value.model == null) return
        val history = _state.value.messages + ChatMessage(Role.USER, text.trim())
        log.clear()
        _state.update { it.copy(messages = history, running = true, error = null, trimmed = 0, truncated = false) }

        job = container.scope.launch {
            var produced: List<ChatMessage> = emptyList()
            // Assistant turns of this run that are already complete; the agent reports only the one being written.
            val finished = ArrayList<ChatMessage>()
            var current = ""
            try {
                val settings = container.runSettings.value
                val session = container.chat.session()
                val params = SamplingParams(temperature = settings.temperature, effort = settings.effort)
                val result = Coder(session, workspace, log, MAX_STEPS).run(
                    project.id, history, params,
                    onChange = { publishFiles(project.id) },
                ) { raw ->
                    if (current.isNotEmpty() && !raw.startsWith(current)) finished += ChatMessage(Role.ASSISTANT, current)
                    current = raw
                    val shown = history + finished + ChatMessage(Role.ASSISTANT, raw)
                    _state.update { if (it.project?.id == project.id) it.copy(messages = shown) else it }
                }
                produced = result.messages
                _state.update { it.copy(trimmed = result.trimmed, truncated = result.stop == StopReason.LENGTH) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PromptTooLongException) {
                _state.update { it.copy(error = CodeError.PromptTooLong) }
            } catch (e: Exception) {
                log.add(LogKind.ERROR, e.message ?: e.toString())
                _state.update { it.copy(error = e.toError()) }
            } finally {
                // Keep whatever was streamed if the run was stopped midway.
                val partial = finished + listOfNotNull(current.takeIf { it.isNotEmpty() }?.let { ChatMessage(Role.ASSISTANT, it) })
                val final = history + produced.ifEmpty { partial }
                _state.update { if (it.project?.id == project.id) it.copy(messages = final, running = false) else it }
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        saveConversation(project.id, final)
                        publishFiles(project.id)
                    }
                }
            }
        }
    }

    /**
     * The tool that lets the main chat hand programming work to the coder.
     * The project it worked on is refreshed here if it is the one on screen.
     */
    fun taskTool(session: TextSession, params: SamplingParams): Tool = CodeTaskTool(
        workspace,
        { session },
        { params },
        onProject = { project ->
            io {
                publishProjects()
                publishFiles(project.id)
            }
        },
    )

    private fun dir(): ProjectDir =
        workspace.open(_state.value.project?.id ?: throw WorkspaceException(Refusal.NOT_FOUND, "No project is open"))

    private fun change(block: (ProjectDir) -> Unit) = io {
        val dir = dir()
        block(dir)
        publishFiles(dir.id)
    }

    private fun io(busy: Boolean = false, block: suspend () -> Unit) {
        if (busy) _state.update { it.copy(busy = true) }
        container.scope.launch {
            try {
                withContext(Dispatchers.IO) { ioLock.withLock { block() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toError()) }
            } finally {
                if (busy) _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun Exception.toError(): CodeError =
        if (this is WorkspaceException) CodeError.Refused(refusal) else CodeError.Other(message ?: toString())

    private fun publishProjects() {
        val projects = workspace.projects()
        _state.update { it.copy(projects = projects) }
    }

    private fun show(project: Project) {
        val dir = workspace.open(project.id)
        val files = dir.files()
        val changes = workspace.changes(project.id).changes(dir)
        val messages = loadConversation(project.id)
        log.clear()
        draft = null
        _state.update { CodeState(projects = it.projects, project = project, files = files, changes = changes, messages = messages) }
    }

    /** Re-reads the files and the changes of a project if it is the one on screen. */
    private fun publishFiles(id: String) {
        if (_state.value.project?.id != id) return
        val dir = workspace.open(id)
        val files = dir.files()
        val changes = workspace.changes(id).changes(dir)
        _state.update {
            if (it.project?.id == id) it.copy(files = files, changes = changes, revision = it.revision + 1) else it
        }
    }

    private fun conversation(id: String) = workspace.sidecar(id, "chat.json")

    private fun loadConversation(id: String): List<ChatMessage> = runCatching {
        val messages = JSONObject(conversation(id).readText()).getJSONArray("messages")
        (0 until messages.length()).mapNotNull { i ->
            val m = messages.getJSONObject(i)
            Role.entries.find { it.wire == m.getString("role") }?.let { ChatMessage(it, m.getString("content")) }
        }
    }.getOrDefault(emptyList())

    private fun saveConversation(id: String, messages: List<ChatMessage>) {
        val json = JSONObject().put(
            "messages",
            JSONArray(messages.map { JSONObject().put("role", it.role.wire).put("content", it.content) }),
        )
        // Written aside first so a crash mid-write cannot destroy the conversation.
        val tmp = workspace.sidecar(id, "chat.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(conversation(id))
    }

    companion object {
        /** A coding task takes a read, a few writes and often a retry; the default of the agent is too tight. */
        private const val MAX_STEPS = 16
    }
}
