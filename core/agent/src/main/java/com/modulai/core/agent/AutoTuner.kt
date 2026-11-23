package com.modulai.core.agent

import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Effort
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.TextEvent
import com.modulai.core.runtime.TextSession
import com.modulai.core.runtime.parseReply

/** Auto mode: trades reasoning depth for responsiveness when the device or the context is strained. */
object AutoTuner {
    /**
     * [tokensPerSecond] is the speed of the previous reply (0 when unknown) and [contextFill]
     * the share of the context window the prompt takes. Never raises effort above [preferred].
     */
    fun effort(preferred: Effort, tokensPerSecond: Float, contextFill: Float): Effort {
        val slow = tokensPerSecond > 0f && tokensPerSecond < 3f
        val sluggish = tokensPerSecond > 0f && tokensPerSecond < 6f
        val cap = when {
            slow || contextFill > 0.75f -> Effort.LOW
            sluggish || contextFill > 0.5f -> Effort.MEDIUM
            else -> Effort.ULTRA
        }
        return if (preferred.ordinal <= cap.ordinal) preferred else cap
    }

    /** Folds [dropped] messages into a running summary so they are not simply forgotten. */
    suspend fun summarize(session: TextSession, previous: String, dropped: List<ChatMessage>): String {
        val transcript = dropped.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .joinToString("\n") { "${it.role.wire}: ${parseReply(it.content).answer.take(1500)}" }
        if (transcript.isBlank()) return previous
        val prompt = "Update the summary of a conversation. Keep names, facts, decisions and anything the " +
            "participants would need later; drop small talk. At most 150 words, in the language of the " +
            "conversation, no preamble.\n\nSummary so far:\n${previous.ifBlank { "(none)" }}\n\nNew messages:\n$transcript"
        val text = StringBuilder()
        session.generate(
            listOf(ChatMessage(Role.USER, prompt)),
            SamplingParams(temperature = 0.3f, maxTokens = 300, effort = Effort.LOW),
        ).collect { if (it is TextEvent.Piece) text.append(it.text) }
        return parseReply(text.toString()).answer.ifBlank { previous }
    }
}
