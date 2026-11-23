package com.modulai.core.agent

import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.StopReason
import com.modulai.core.runtime.TextEvent
import com.modulai.core.runtime.TextSession
import com.modulai.core.runtime.fitToContext
import com.modulai.core.runtime.parseReply
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class AgentResult(
    /** Everything produced in this run, in order: assistant turns and tool results. */
    val messages: List<ChatMessage>,
    val stop: StopReason,
    /** Messages dropped from the prompt to make it fit. */
    val trimmed: Int,
)

/**
 * Runs the model until it answers without asking for a tool, executing tool calls in between.
 * [onText] receives the raw text of the assistant turn being generated, replaced on every piece.
 */
class Agent(
    private val session: TextSession,
    private val tools: List<Tool>,
    private val log: AgentLog,
    private val maxSteps: Int = 6,
) {
    suspend fun run(
        history: List<ChatMessage>,
        params: SamplingParams,
        onText: (String) -> Unit,
    ): AgentResult {
        val specs = tools.map { it.spec }
        val produced = ArrayList<ChatMessage>()
        var stop = StopReason.END
        var trimmed = 0

        for (step in 0 until maxSteps) {
            // On the last step the tools are withheld so the model has to answer.
            val offered = if (step == maxSteps - 1) emptyList() else specs
            val full = history + produced
            val prompt = session.fitToContext(full, params.effort, tools = offered)
            trimmed = full.size - prompt.size

            val text = StringBuilder()
            val calls = ArrayList<TextEvent.ToolCall>()
            session.generate(prompt, params, offered).collect { event ->
                when (event) {
                    is TextEvent.Piece -> {
                        text.append(event.text)
                        onText(text.toString())
                    }
                    is TextEvent.ToolCall -> calls += event
                    is TextEvent.Done -> stop = event.reason
                }
            }
            produced += ChatMessage(Role.ASSISTANT, text.toString())
            parseReply(text.toString()).thinking.takeIf { it.isNotEmpty() }?.let { log.add(LogKind.THOUGHT, it.take(80), it) }

            if (calls.isEmpty() || stop == StopReason.CANCELLED) break
            for (call in calls) produced += ChatMessage(Role.TOOL, execute(call))
        }
        return AgentResult(produced, stop, trimmed)
    }

    private suspend fun execute(call: TextEvent.ToolCall): String {
        log.add(LogKind.TOOL_CALL, call.name, call.argumentsJson)
        val tool = tools.find { it.spec.name == call.name }
            ?: return "Error: unknown tool ${call.name}".also { log.add(LogKind.ERROR, it) }
        return try {
            val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
            tool.run(args, log)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The model gets the failure as the tool result and can react to it.
            "Error: ${e.message ?: e.javaClass.simpleName}".also { log.add(LogKind.ERROR, call.name, it) }
        }
    }
}
