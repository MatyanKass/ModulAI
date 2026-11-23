package com.modulai.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogKind { THOUGHT, TOOL_CALL, TOOL_RESULT, SEARCH, PAGE, NOTE, ERROR }

data class LogEntry(val kind: LogKind, val title: String, val detail: String = "", val timeMillis: Long)

/** What the model did behind the chat: tool calls, searches, pages read. Hidden by default in the UI. */
class AgentLog(private val clock: () -> Long = System::currentTimeMillis) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun add(kind: LogKind, title: String, detail: String = "") {
        _entries.update { (it + LogEntry(kind, title, detail.take(MAX_DETAIL), clock())).takeLast(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val MAX_DETAIL = 4000
    }
}
