package com.modulai.core.characters

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Characters on disk: `<id>.json` (Character Card V2) with an optional `<id>.avatar` image beside it.
 * The plain functions do blocking file I/O; call them off the main thread.
 */
class CharacterStore(private val context: Context) {
    private val root = File(context.filesDir, "characters")
    private val cards = File(root, "cards").apply { mkdirs() }
    private val personaFile = File(root, "persona.json")

    fun list(): List<CharacterCard> =
        cards.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { get(it.name.removeSuffix(".json")) }
            .sortedBy { it.name.lowercase() }

    fun get(id: String): CharacterCard? {
        if (!ID.matches(id)) return null
        val file = File(cards, "$id.json")
        if (!file.isFile) return null
        return try {
            CardCodec.parseJson(file.readText()).copy(id = id, avatarPath = avatarPath(id))
        } catch (e: CardFormatException) {
            null
        }
    }

    /** Writes [card], giving it an id when it is new, and returns it as stored. */
    fun save(card: CharacterCard): CharacterCard {
        val id = card.id.takeIf { ID.matches(it) } ?: UUID.randomUUID().toString()
        writeAtomically(File(cards, "$id.json"), CardCodec.toV2Json(card).toByteArray())
        return card.copy(id = id, avatarPath = avatarPath(id))
    }

    fun delete(id: String) {
        if (!ID.matches(id)) return
        File(cards, "$id.json").delete()
        avatarFile(id).delete()
    }

    /** Imports a PNG or JSON card picked through the system picker; a PNG card doubles as the avatar. */
    suspend fun import(uri: Uri): CharacterCard = withContext(Dispatchers.IO) {
        val bytes = read(uri, CardCodec.MAX_FILE_BYTES)
        val saved = save(CardCodec.parse(bytes))
        if (!PngText.isPng(bytes)) return@withContext saved
        writeAtomically(avatarFile(saved.id), bytes)
        saved.copy(avatarPath = avatarPath(saved.id))
    }

    /** Copies the picked image as the avatar of the stored card [id]; null removes the avatar. */
    suspend fun setAvatar(id: String, image: Uri?): CharacterCard? = withContext(Dispatchers.IO) {
        if (!ID.matches(id)) return@withContext null
        if (image == null) avatarFile(id).delete() else writeAtomically(avatarFile(id), read(image, MAX_AVATAR_BYTES))
        get(id)
    }

    /** Writes [card] as Character Card V2 JSON to a document created through the system picker. */
    suspend fun export(card: CharacterCard, target: Uri) = withContext(Dispatchers.IO) {
        // "wt" truncates: without it a provider may keep the tail of a longer file being overwritten.
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(CardCodec.toV2Json(card).toByteArray()) }
            ?: throw IOException("Could not open the file for writing")
    }

    fun persona(): Persona =
        try {
            val json = JSONObject(personaFile.readText())
            Persona(json.opt("name") as? String ?: "", json.opt("description") as? String ?: "")
        } catch (e: IOException) {
            Persona()
        } catch (e: JSONException) {
            Persona()
        }

    fun savePersona(persona: Persona) {
        val json = JSONObject().put("name", persona.name).put("description", persona.description)
        writeAtomically(personaFile, json.toString().toByteArray())
    }

    private fun avatarFile(id: String) = File(cards, "$id.avatar")

    private fun avatarPath(id: String) = avatarFile(id).takeIf { it.isFile }?.absolutePath

    private fun read(uri: Uri, limit: Int): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { readAtMost(it, limit) }
            ?: throw IOException("Could not open the file")

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Could not store ${target.name}")
        }
    }

    companion object {
        private const val MAX_AVATAR_BYTES = 32 shl 20
        private val ID = Regex("""[A-Za-z0-9_-]{1,64}""")

        /** Reads a whole stream but gives up past [limit], whatever size the provider claimed. */
        internal fun readAtMost(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                out.write(buffer, 0, n)
                if (out.size() > limit) throw CardFormatException(CardError.TOO_LARGE, "The file is too large")
            }
        }
    }
}
