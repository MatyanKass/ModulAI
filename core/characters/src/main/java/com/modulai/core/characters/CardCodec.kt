package com.modulai.core.characters

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** Reads Character Card V1/V2/V3 from JSON or PNG and writes V2 JSON. */
object CardCodec {
    /** Cards are text plus one image; anything bigger is not a card and is not worth loading into memory. */
    const val MAX_FILE_BYTES = 32 shl 20

    /** Parses a card file, telling PNG from JSON by content rather than by name. */
    fun parse(bytes: ByteArray): CharacterCard {
        if (bytes.size > MAX_FILE_BYTES) {
            throw CardFormatException(CardError.TOO_LARGE, "The file is too large to be a character card")
        }
        return if (PngText.isPng(bytes)) parsePng(bytes) else parseJson(String(bytes, Charsets.UTF_8))
    }

    fun parsePng(bytes: ByteArray): CharacterCard {
        val chunks = PngText.read(bytes)
        // A V3 card carries both chunks; "chara" is then only a V2 copy for older software.
        val encoded = chunks["ccv3"] ?: chunks["chara"]
            ?: throw CardFormatException(CardError.NO_CARD_IN_PNG, "This PNG image has no character card inside")
        val json = try {
            String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw CardFormatException(CardError.BAD_JSON, "The card data in this PNG is damaged")
        }
        return parseJson(json)
    }

    fun parseJson(text: String): CharacterCard {
        val root = try {
            JSONObject(text.removePrefix("﻿").trim())
        } catch (e: JSONException) {
            throw CardFormatException(CardError.BAD_JSON, "Not valid JSON: ${e.message}")
        }
        // V2 and V3 wrap the fields in "data"; V1 keeps them at the top level.
        val data = root.optJSONObject("data")?.takeIf { root.has("spec") || !root.has("name") } ?: root
        val name = data.text("name", "char_name")
        if (name.isBlank()) throw CardFormatException(CardError.NOT_A_CARD, "This JSON file is not a character card")
        return CharacterCard(
            name = name.trim(),
            description = data.text("description", "char_persona"),
            personality = data.text("personality"),
            scenario = data.text("scenario", "world_scenario"),
            firstMessage = data.text("first_mes", "char_greeting"),
            alternateGreetings = data.strings("alternate_greetings"),
            exampleDialogue = data.text("mes_example", "example_dialogue"),
            systemPrompt = data.text("system_prompt"),
            postHistoryInstructions = data.text("post_history_instructions"),
            creatorNotes = data.text("creator_notes", "creatorcomment"),
            tags = data.strings("tags").map { it.trim() }.filter { it.isNotEmpty() },
            creator = data.text("creator"),
            lorebook = data.optJSONObject("character_book")?.let(::lorebook).orEmpty(),
        )
    }

    /** Character Card V2 JSON, the format every frontend accepts. */
    fun toV2Json(card: CharacterCard): String {
        val data = JSONObject()
            .put("name", card.name)
            .put("description", card.description)
            .put("personality", card.personality)
            .put("scenario", card.scenario)
            .put("first_mes", card.firstMessage)
            .put("mes_example", card.exampleDialogue)
            .put("creator_notes", card.creatorNotes)
            .put("system_prompt", card.systemPrompt)
            .put("post_history_instructions", card.postHistoryInstructions)
            .put("alternate_greetings", JSONArray(card.alternateGreetings))
            .put("tags", JSONArray(card.tags))
            .put("creator", card.creator)
            .put("character_version", "")
            .put("extensions", JSONObject())
        if (card.lorebook.isNotEmpty()) {
            val entries = JSONArray()
            card.lorebook.forEach { entry ->
                entries.put(
                    JSONObject()
                        .put("keys", JSONArray(entry.keys))
                        .put("content", entry.content)
                        .put("extensions", JSONObject())
                        .put("enabled", entry.enabled)
                        .put("insertion_order", entry.insertionOrder)
                        .put("constant", entry.constant),
                )
            }
            data.put("character_book", JSONObject().put("extensions", JSONObject()).put("entries", entries))
        }
        return JSONObject().put("spec", "chara_card_v2").put("spec_version", "2.0").put("data", data).toString(2)
    }

    private fun lorebook(book: JSONObject): List<LorebookEntry> {
        // The card spec uses an array; SillyTavern world-info exports use an object keyed by entry id.
        val raw = ArrayList<JSONObject>()
        book.optJSONArray("entries")?.let { array ->
            for (i in 0 until array.length()) array.optJSONObject(i)?.let(raw::add)
        }
        book.optJSONObject("entries")?.let { map ->
            map.keys().forEach { key -> map.optJSONObject(key)?.let(raw::add) }
        }
        return raw.map { entry ->
            LorebookEntry(
                keys = (entry.strings("keys") + entry.strings("key")).map { it.trim() }.filter { it.isNotEmpty() },
                content = entry.text("content"),
                enabled = if (entry.has("enabled")) entry.optBoolean("enabled", true) else !entry.optBoolean("disable", false),
                constant = entry.optBoolean("constant", false),
                insertionOrder = if (entry.has("insertion_order")) entry.optInt("insertion_order") else entry.optInt("order"),
            )
        }.filter { it.content.isNotBlank() }
    }

    /** The first of [names] holding a string. optString is avoided: on Android it turns JSON null into "null". */
    private fun JSONObject.text(vararg names: String): String =
        names.firstNotNullOfOrNull { opt(it) as? String } ?: ""

    private fun JSONObject.strings(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.opt(it) as? String }
    }
}
