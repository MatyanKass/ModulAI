package com.modulai.core.characters

private const val DEFAULT_USER_NAME = "User"

private const val DEFAULT_INSTRUCTION =
    "You are {{char}} in a never-ending fictional roleplay chat with {{user}}. " +
        "Write {{char}}'s next reply: stay true to the character's personality and voice, " +
        "describe actions and surroundings as well as speech, and move the scene forward."

private const val EMOJI_INSTRUCTION = "Use emoji naturally in your replies where they fit the mood."

private val MACRO = Regex("""\{\{(char|user)\}\}|<(user|bot)>""", RegexOption.IGNORE_CASE)
private val START_MARKER = Regex("""<START>""", RegexOption.IGNORE_CASE)

/** Replaces `{{char}}`, `{{user}}` (any case) and the older `<BOT>`, `<USER>` with the actual names. */
fun applyMacros(text: String, card: CharacterCard, persona: Persona): String {
    val user = persona.name.trim().ifEmpty { DEFAULT_USER_NAME }
    return MACRO.replace(text) { match ->
        val macro = (match.groupValues[1] + match.groupValues[2]).lowercase()
        if (macro == "user") user else card.name
    }
}

/**
 * The single system prompt of a roleplay chat. [emoji] is the "emoji style" option.
 * The card's post-history instructions are not included: they belong after the chat history.
 */
fun buildSystemPrompt(card: CharacterCard, persona: Persona, emoji: Boolean = false): String {
    val sections = ArrayList<String>()
    fun add(text: String, title: String? = null) {
        if (text.isNotBlank()) sections += if (title == null) text.trim() else "$title\n${text.trim()}"
    }
    // Cards may wrap the frontend's own prompt instead of replacing it.
    add(card.systemPrompt.ifBlank { DEFAULT_INSTRUCTION }.replace("{{original}}", DEFAULT_INSTRUCTION, ignoreCase = true))
    add(card.description)
    add(card.personality, "{{char}}'s personality:")
    add(card.scenario, "Scenario:")
    if (persona.description.isNotBlank()) {
        add(persona.description, "About {{user}}, the person you are talking to:")
    } else if (persona.name.isNotBlank()) {
        add("The person you are talking to is called {{user}}.")
    }
    add(
        card.exampleDialogue.replace(START_MARKER, "").trim(),
        "Examples of how {{char}} talks (a style reference, not part of this chat):",
    )
    add(
        "Stay in character as {{char}} at all times and never speak or act for {{user}}. " +
            "Reply in the language {{user}} writes in." + if (emoji) " $EMOJI_INSTRUCTION" else "",
    )
    return applyMacros(sections.joinToString("\n\n"), card, persona)
}

/**
 * The opening message number [index] of [CharacterCard.greetings] with macros replaced.
 * An index out of range gives the first greeting; a card without greetings gives an empty string.
 */
fun greeting(card: CharacterCard, persona: Persona, index: Int = 0): String {
    val all = card.greetings
    return applyMacros(all.getOrNull(index) ?: all.firstOrNull() ?: "", card, persona)
}

/**
 * Lorebook entries to add to the prompt for the current turn: enabled entries that are constant or
 * have a key occurring in [recentText] (case-insensitive), in insertion order. Entries that would
 * push the total past [budgetChars] are skipped, so a long entry does not block shorter ones after it.
 */
fun activeLorebook(card: CharacterCard, persona: Persona, recentText: String, budgetChars: Int = 2000): List<String> {
    var left = budgetChars
    return card.lorebook
        .filter { entry ->
            entry.enabled && (entry.constant || entry.keys.any { it.isNotBlank() && recentText.contains(it.trim(), ignoreCase = true) })
        }
        .sortedBy { it.insertionOrder }
        .map { applyMacros(it.content.trim(), card, persona) }
        .filter { content ->
            (content.isNotEmpty() && content.length <= left).also { fits -> if (fits) left -= content.length }
        }
}
