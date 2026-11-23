package com.modulai.app

import com.modulai.core.agent.Agent
import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.FetchPageTool
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.ResearchTool
import com.modulai.core.agent.Researcher
import com.modulai.core.agent.Tool
import com.modulai.core.agent.WebSearchTool
import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.PromptTooLongException
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.StopReason
import com.modulai.core.runtime.TextSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ChatState(
    val chat: StoredChat = StoredChat(),
    val model: ModelDescriptor? = null,
    val loading: Boolean = false,
    val generating: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val usedTokens: Int = 0,
    val contextTokens: Int = 0,
    /** Messages dropped from the prompt because the conversation outgrew the context. */
    val trimmed: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
) {
    val messages get() = chat.messages
}

/** Owns the loaded text model and the conversation on screen. */
class ChatController(private val container: AppContainer) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    /** What the model did behind the conversation; shown in the "workspace" sheet. */
    val log = AgentLog()

    private var session: TextSession? = null
    private var loadedWith: LoadConfig? = null
    private var job: Job? = null
    private val loadLock = Mutex()

    fun select(model: ModelDescriptor?) {
        stop()
        unload()
        _state.update { it.copy(model = model, error = null, usedTokens = 0, contextTokens = 0) }
    }

    fun newChat(systemPrompt: String? = null, characterName: String? = null, greeting: String? = null) {
        stop()
        log.clear()
        val messages = listOfNotNull(greeting?.takeIf { it.isNotBlank() }?.let { ChatMessage(Role.ASSISTANT, it) })
        _state.update {
            it.copy(
                chat = StoredChat(
                    title = characterName.orEmpty(),
                    systemPrompt = systemPrompt,
                    characterName = characterName,
                    messages = messages,
                ),
                error = null, usedTokens = 0, trimmed = 0, truncated = false,
            )
        }
    }

    fun open(id: String) {
        stop()
        log.clear()
        val chat = container.chats.load(id) ?: return
        _state.update { it.copy(chat = chat, error = null, usedTokens = 0, trimmed = 0, truncated = false) }
    }

    fun delete(id: String) {
        container.chats.delete(id)
        if (_state.value.chat.id == id) newChat()
    }

    fun stop() {
        job?.cancel()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** The loaded model, loading it first if needed. Used by modules that share the chat model. */
    suspend fun session(): TextSession {
        val model = _state.value.model ?: error("No model selected")
        return ensureLoaded(model, container.runSettings.value)
    }

    fun send(text: String) {
        val model = _state.value.model ?: return
        if (_state.value.generating || _state.value.loading || text.isBlank()) return
        val base = _state.value.chat
        val history = base.messages + ChatMessage(Role.USER, text.trim())
        val title = base.title.ifEmpty { text.trim().lineSequence().first().take(60) }
        _state.update { it.copy(chat = base.copy(messages = history, title = title), error = null, truncated = false) }

        job = container.scope.launch {
            var produced: List<ChatMessage> = emptyList()
            try {
                val settings = container.runSettings.value
                val s = ensureLoaded(model, settings)
                val system = (base.systemPrompt ?: settings.systemPrompt).trim()
                val prompt = if (system.isEmpty()) history else listOf(ChatMessage(Role.SYSTEM, system)) + history
                val params = SamplingParams(temperature = settings.temperature, effort = settings.effort)
                val tools = if (settings.webTools) webTools(s, params, settings) else emptyList()
                val promptTokens = s.countTokens(prompt, settings.effort, tools.map { it.spec })

                _state.update {
                    it.copy(generating = true, usedTokens = promptTokens, contextTokens = s.contextTokens, tokensPerSecond = 0f)
                }
                show(history + ChatMessage(Role.ASSISTANT, ""))

                var pieces = 0
                var firstPieceAt = 0L
                val result = Agent(s, tools, log).run(prompt, params) { raw ->
                    val now = System.nanoTime()
                    if (pieces == 0) firstPieceAt = now
                    pieces++
                    val seconds = (now - firstPieceAt) / 1e9f
                    _state.update {
                        it.copy(
                            chat = it.chat.copy(messages = history + produced + ChatMessage(Role.ASSISTANT, raw)),
                            usedTokens = promptTokens + pieces,
                            tokensPerSecond = if (pieces > 1 && seconds > 0) (pieces - 1) / seconds else 0f,
                        )
                    }
                }
                produced = result.messages
                _state.update { it.copy(trimmed = result.trimmed, truncated = result.stop == StopReason.LENGTH) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PromptTooLongException) {
                _state.update { it.copy(error = ERROR_TOO_LONG) }
            } catch (e: Exception) {
                log.add(LogKind.ERROR, e.message ?: e.toString())
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                // Keep whatever was streamed if generation was stopped midway.
                val shown = _state.value.chat.messages
                val final = if (produced.isNotEmpty()) history + produced else shown.filterNot {
                    it.role == Role.ASSISTANT && it.content.isEmpty()
                }
                val saved = _state.value.chat.copy(messages = final, updatedAt = System.currentTimeMillis())
                _state.update { it.copy(generating = false, loading = false, chat = saved) }
                withContext(Dispatchers.IO) { container.chats.save(saved) }
            }
        }
    }

    private fun show(messages: List<ChatMessage>) = _state.update { it.copy(chat = it.chat.copy(messages = messages)) }

    private fun webTools(s: TextSession, params: SamplingParams, settings: RunSettings): List<Tool> = listOf(
        WebSearchTool(container.web),
        FetchPageTool(container.web, maxChars = s.contextTokens.coerceIn(1500, 8000)),
        ResearchTool(Researcher(s, container.web, log), { params }, { settings.researchDepth }),
    )

    private suspend fun ensureLoaded(model: ModelDescriptor, settings: RunSettings): TextSession = loadLock.withLock {
        val choice = container.engines.resolve(model, container.device.inspect().backends)
            ?: error("No engine can run ${model.id}")
        val config = LoadConfig(
            backend = choice.backend,
            contextTokens = settings.contextTokens,
            threads = settings.threads,
            lockMemory = settings.lockMemory,
            useMmap = settings.useMmap,
        )
        session?.let { if (loadedWith == config && it.model == model) return it }
        unload()
        _state.update { it.copy(loading = true) }
        try {
            val s = choice.engine.load(model, config) as TextSession
            session = s
            loadedWith = config
            s
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private fun unload() {
        session?.close()
        session = null
        loadedWith = null
    }

    companion object {
        /** Marker replaced by a localized string in the UI. */
        const val ERROR_TOO_LONG = "\u0000too_long"
    }
}
