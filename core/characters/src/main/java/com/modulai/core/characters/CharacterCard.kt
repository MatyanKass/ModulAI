package com.modulai.core.characters

/** One lorebook entry: [content] is added to the prompt while any of [keys] is being talked about. */
data class LorebookEntry(
    val keys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    /** Constant entries are always active, whatever the keys. */
    val constant: Boolean = false,
    val insertionOrder: Int = 0,
)

/**
 * A character in the community "Character Card" shape (V1, V2 and V3 share these fields).
 * An empty [id] marks a card that has not been saved yet.
 */
data class CharacterCard(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val avatarPath: String? = null,
    val lorebook: List<LorebookEntry> = emptyList(),
) {
    /** The first message followed by the alternate greetings, blank ones dropped. */
    val greetings: List<String>
        get() = (listOf(firstMessage) + alternateGreetings).filter { it.isNotBlank() }
}

/** Who the user plays in a roleplay chat. */
data class Persona(val name: String = "", val description: String = "")

enum class CardError { TOO_LARGE, BAD_PNG, NO_CARD_IN_PNG, BAD_JSON, NOT_A_CARD }

/** A file that could not be read as a character card; [error] lets the UI explain it in the user's language. */
class CardFormatException(val error: CardError, message: String) : Exception(message)
