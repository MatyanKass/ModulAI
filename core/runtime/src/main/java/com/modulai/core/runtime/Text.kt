package com.modulai.core.runtime

import kotlinx.coroutines.flow.Flow

enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool") }

/** A function the model may call. [parametersSchema] is a JSON Schema object as text. */
data class ToolSpec(val name: String, val description: String, val parametersSchema: String)

data class ChatMessage(val role: Role, val content: String)

/**
 * How much the model may reason before answering.
 * [thinkingBudget] is a token cap on the reasoning block; 0 means no cap.
 */
enum class Effort(val thinking: Boolean, val thinkingBudget: Int) {
    LOW(false, 0),
    MEDIUM(true, 512),
    HIGH(true, 2048),
    ULTRA(true, 0),
}

data class SamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    /** Cap on generated tokens; 0 means until the context is full. */
    val maxTokens: Int = 0,
    /** Negative picks a random seed. */
    val seed: Int = -1,
    val effort: Effort = Effort.LOW,
)

enum class StopReason { END, LENGTH, CANCELLED }

sealed interface TextEvent {
    /** A piece of generated text, roughly one token. */
    data class Piece(val text: String) : TextEvent
    /** The model asked to run a tool; arrives after all the text, before [Done]. */
    data class ToolCall(val name: String, val argumentsJson: String) : TextEvent
    data class Done(val reason: StopReason) : TextEvent
}

class PromptTooLongException : Exception("Prompt does not fit into the context window")
class GenerationException(message: String) : Exception(message)

interface TextSession : Session {
    val contextTokens: Int

    /** Tokens the rendered prompt for [messages] would take. */
    suspend fun countTokens(messages: List<ChatMessage>, effort: Effort, tools: List<ToolSpec> = emptyList()): Int

    /**
     * Streams a reply to the whole conversation. Cancelling the collector stops generation.
     * Fails with [PromptTooLongException] when [messages] do not fit; see [fitToContext].
     */
    fun generate(
        messages: List<ChatMessage>,
        params: SamplingParams,
        tools: List<ToolSpec> = emptyList(),
    ): Flow<TextEvent>
}

/**
 * Drops the oldest non-system messages until the prompt leaves [reserve] tokens for the reply.
 * The system prompt and the latest message always stay.
 */
suspend fun TextSession.fitToContext(
    messages: List<ChatMessage>,
    effort: Effort,
    reserve: Int = contextTokens / 4,
    tools: List<ToolSpec> = emptyList(),
): List<ChatMessage> {
    var current = messages
    while (countTokens(current, effort, tools) > contextTokens - reserve) {
        val drop = current.indexOfFirst { it.role != Role.SYSTEM }
        if (drop < 0 || drop == current.lastIndex) break
        current = current.filterIndexed { i, _ -> i != drop }
    }
    return current
}

/** Splits raw model output into the reasoning block and the visible answer. */
data class ParsedReply(val thinking: String, val answer: String, val thinkingOpen: Boolean)

fun parseReply(raw: String, startTag: String = "<think>", endTag: String = "</think>"): ParsedReply {
    val start = raw.indexOf(startTag)
    if (start < 0) {
        // Some templates open the block in the prompt, so only the closing tag is generated.
        val end = raw.indexOf(endTag)
        return if (end < 0) ParsedReply("", raw.trim(), false)
        else ParsedReply(raw.substring(0, end).trim(), raw.substring(end + endTag.length).trim(), false)
    }
    val end = raw.indexOf(endTag, start)
    return if (end < 0) {
        ParsedReply(raw.substring(start + startTag.length).trim(), raw.substring(0, start).trim(), true)
    } else {
        ParsedReply(
            raw.substring(start + startTag.length, end).trim(),
            (raw.substring(0, start) + raw.substring(end + endTag.length)).trim(),
            false,
        )
    }
}
