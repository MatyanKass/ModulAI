package com.modulai.core.characters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    private val card = CharacterCard(
        name = "Mira",
        description = "DESC {{char}} knows <USER>.",
        personality = "PERSONALITY",
        scenario = "SCENARIO with {{User}}",
        firstMessage = "Hello, {{USER}}. I am <bot>.",
        alternateGreetings = listOf("", "Second, {{user}}"),
        exampleDialogue = "<START>\n{{user}}: hi\n{{char}}: EXAMPLE",
        lorebook = listOf(
            LorebookEntry(listOf("Dragon"), "LATE {{char}}", insertionOrder = 30),
            LorebookEntry(listOf("castle", "keep"), "EARLY", insertionOrder = 10),
            LorebookEntry(emptyList(), "ALWAYS", constant = true, insertionOrder = 20),
            LorebookEntry(listOf("dragon"), "DISABLED", enabled = false),
            LorebookEntry(listOf("wizard"), "UNMENTIONED"),
        ),
    )
    private val persona = Persona("Alex", "TRAVELLER from afar")

    @Test
    fun sectionsComeInTheConventionalOrder() {
        val prompt = buildSystemPrompt(card, persona)
        val order = listOf("You are Mira", "DESC", "PERSONALITY", "SCENARIO", "TRAVELLER", "EXAMPLE", "Stay in character", "language Alex writes in")
        val positions = order.map { prompt.indexOf(it) }
        assertTrue(prompt, positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertFalse(prompt.contains("<START>"))
    }

    @Test
    fun macrosAreReplacedInEveryForm() {
        val prompt = buildSystemPrompt(card, persona)
        assertTrue(prompt.contains("DESC Mira knows Alex."))
        assertTrue(prompt.contains("SCENARIO with Alex"))
        assertFalse(Regex("""\{\{|<user>|<bot>""", RegexOption.IGNORE_CASE).containsMatchIn(prompt))
        assertEquals("Mira and User, \$1", applyMacros("{{CHAR}} and <user>, \$1", card, Persona()))
        assertEquals("a \$0 b", applyMacros("a {{user}} b", card, Persona("\$0")))
    }

    @Test
    fun cardSystemPromptReplacesOrWrapsTheDefault() {
        val own = buildSystemPrompt(card.copy(systemPrompt = "OWN RULES for {{char}}"), persona)
        assertTrue(own.startsWith("OWN RULES for Mira"))
        assertFalse(own.contains("You are Mira in"))
        val wrapped = buildSystemPrompt(card.copy(systemPrompt = "{{original}} EXTRA"), persona)
        assertTrue(wrapped.startsWith("You are Mira in"))
        assertTrue(wrapped.contains("EXTRA"))
    }

    @Test
    fun emptyFieldsLeaveNoHeadings() {
        val prompt = buildSystemPrompt(CharacterCard(name = "Mira"), Persona())
        assertFalse(prompt.contains("personality:"))
        assertFalse(prompt.contains("Scenario:"))
        assertFalse(prompt.contains("About"))
        assertFalse(prompt.contains("Examples"))
        assertTrue(prompt.contains("User"))
        assertTrue(buildSystemPrompt(CharacterCard(name = "Mira"), Persona("Alex")).contains("is called Alex"))
    }

    @Test
    fun emojiOptionAddsOneSentence() {
        assertFalse(buildSystemPrompt(card, persona).contains("emoji"))
        assertTrue(buildSystemPrompt(card, persona, emoji = true).endsWith("Use emoji naturally in your replies where they fit the mood."))
    }

    @Test
    fun greetingIndexesNonBlankGreetings() {
        assertEquals(2, card.greetings.size)
        assertEquals("Hello, Alex. I am Mira.", greeting(card, persona))
        assertEquals("Second, Alex", greeting(card, persona, 1))
        assertEquals("Hello, Alex. I am Mira.", greeting(card, persona, 7))
        assertEquals("", greeting(CharacterCard(name = "Mira"), persona, 0))
    }

    @Test
    fun lorebookActivatesByKeyInInsertionOrder() {
        assertEquals(listOf("ALWAYS"), activeLorebook(card, persona, "nothing relevant"))
        assertEquals(
            listOf("EARLY", "ALWAYS", "LATE Mira"),
            activeLorebook(card, persona, "The DRAGON circled the Keep."),
        )
    }

    @Test
    fun lorebookRespectsTheBudget() {
        val text = "dragon castle"
        assertEquals(listOf("EARLY", "ALWAYS"), activeLorebook(card, persona, text, budgetChars = 11))
        // The entry that does not fit is skipped without blocking a shorter one after it.
        assertEquals(listOf("EARLY"), activeLorebook(card, persona, text, budgetChars = 5))
        assertEquals(emptyList<String>(), activeLorebook(card, persona, text, budgetChars = 0))
        val long = card.copy(lorebook = listOf(LorebookEntry(listOf("dragon"), "X".repeat(50), insertionOrder = 1)) + card.lorebook)
        assertEquals(listOf("EARLY", "ALWAYS", "LATE Mira"), activeLorebook(long, persona, text, budgetChars = 30))
    }
}
