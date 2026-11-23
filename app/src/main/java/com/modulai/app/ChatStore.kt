package com.modulai.app

import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class StoredChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val updatedAt: Long = 0,
    val messages: List<ChatMessage> = emptyList(),
    /** Set for roleplay chats; replaces the global system prompt. */
    val systemPrompt: String? = null,
    val characterName: String? = null,
    val characterId: String? = null,
)

/** One JSON file per conversation. Small enough that a database would only add weight. */
class ChatStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    /** Newest first; messages are not loaded. */
    fun list(): List<StoredChat> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { parse(f.readText(), withMessages = false) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun load(id: String): StoredChat? =
        runCatching { parse(File(dir, "$id.json").readText(), withMessages = true) }.getOrNull()

    fun save(chat: StoredChat) {
        val json = JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("updatedAt", chat.updatedAt)
            .put("systemPrompt", chat.systemPrompt ?: JSONObject.NULL)
            .put("characterName", chat.characterName ?: JSONObject.NULL)
            .put("characterId", chat.characterId ?: JSONObject.NULL)
            .put("messages", JSONArray(chat.messages.map { JSONObject().put("role", it.role.wire).put("content", it.content) }))
        // Write to a temp file first so a crash mid-write cannot destroy the conversation.
        val tmp = File(dir, "${chat.id}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, "${chat.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    private fun parse(text: String, withMessages: Boolean): StoredChat {
        val o = JSONObject(text)
        val messages = if (!withMessages) emptyList() else o.getJSONArray("messages").let { a ->
            (0 until a.length()).mapNotNull { i ->
                val m = a.getJSONObject(i)
                val role = Role.entries.find { it.wire == m.getString("role") } ?: return@mapNotNull null
                ChatMessage(role, m.getString("content"))
            }
        }
        return StoredChat(
            id = o.getString("id"),
            title = o.optString("title"),
            updatedAt = o.optLong("updatedAt"),
            messages = messages,
            systemPrompt = if (o.isNull("systemPrompt")) null else o.getString("systemPrompt"),
            characterName = if (o.isNull("characterName")) null else o.getString("characterName"),
            characterId = if (o.isNull("characterId")) null else o.getString("characterId"),
        )
    }
}
