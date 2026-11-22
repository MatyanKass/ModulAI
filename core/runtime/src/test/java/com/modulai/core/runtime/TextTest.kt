package com.modulai.core.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TextTest {
    @Test fun plainAnswer() {
        assertEquals(ParsedReply("", "hi", false), parseReply(" hi "))
    }

    @Test fun closedThinking() {
        assertEquals(ParsedReply("plan", "answer", false), parseReply("<think>\nplan\n</think>\n\nanswer"))
    }

    @Test fun openThinkingWhileStreaming() {
        assertEquals(ParsedReply("pla", "", true), parseReply("<think>pla"))
    }

    @Test fun onlyClosingTag() {
        assertEquals(ParsedReply("plan", "answer", false), parseReply("plan</think>answer"))
    }

    /** One token per word, plus a fixed template overhead per message. */
    private class WordSession(override val contextTokens: Int) : TextSession {
        override val model get() = error("unused")
        override val backend = Backend.CPU
        override suspend fun countTokens(messages: List<ChatMessage>, effort: Effort) =
            messages.sumOf { it.content.split(' ').size + 2 }
        override fun generate(messages: List<ChatMessage>, params: SamplingParams): Flow<TextEvent> = emptyFlow()
        override fun close() = Unit
    }

    private fun msg(role: Role, words: Int) = ChatMessage(role, List(words) { "w" }.joinToString(" "))

    @Test fun trimsOldestButKeepsSystemAndLast() = runTest {
        val history = listOf(msg(Role.SYSTEM, 8), msg(Role.USER, 30), msg(Role.ASSISTANT, 30), msg(Role.USER, 8))
        val fitted = WordSession(64).fitToContext(history, Effort.LOW, reserve = 16)
        assertEquals(listOf(history[0], history[3]), fitted)
    }

    @Test fun keepsEverythingWhenItFits() = runTest {
        val history = listOf(msg(Role.USER, 4), msg(Role.ASSISTANT, 4), msg(Role.USER, 4))
        assertEquals(history, WordSession(64).fitToContext(history, Effort.LOW, reserve = 16))
    }

    @Test fun neverDropsTheLastMessage() = runTest {
        val history = listOf(msg(Role.USER, 500))
        assertEquals(history, WordSession(64).fitToContext(history, Effort.LOW, reserve = 16))
    }
}
