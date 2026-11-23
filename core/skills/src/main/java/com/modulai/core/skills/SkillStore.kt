package com.modulai.core.skills

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest

/**
 * Installed skills, one folder per skill under [root], plus `state.json` with what is switched off.
 * All methods touch the disk; call them off the main thread.
 */
class SkillStore(private val root: File) {
    private val stateFile = File(root, "state.json")
    private var cache: List<Skill>? = null

    @Synchronized
    fun list(): List<Skill> = cache ?: load().also { cache = it }

    fun get(id: String): Skill? = list().find { it.id == id }

    /** Installs a skill given as a ZIP archive or as the text of a SKILL.md, whichever [stream] holds. */
    fun install(stream: InputStream, fallbackName: String): Skill {
        val input = PushbackInputStream(stream, 4)
        val head = input.readAtMost(4)
        input.unread(head)
        if (SkillArchive.isZip(head)) return install(SkillArchive.read(input, fallbackName))
        // One byte past the limit is enough to tell an oversized file from a full one.
        val bytes = input.readAtMost(SkillArchive.MAX_FILE_BYTES + 1)
        if (bytes.size > SkillArchive.MAX_FILE_BYTES) throw SkillFormatException("SKILL.md is too large")
        if (bytes.contains(0)) throw SkillFormatException("Not a text file")
        return installMarkdown(String(bytes, Charsets.UTF_8), fallbackName)
    }

    fun installMarkdown(text: String, fallbackName: String): Skill = install(SkillBundle(text, emptyMap(), fallbackName))

    /** Adds the skill, replacing an installed one with the same name. Whether it is enabled is kept. */
    @Synchronized
    fun install(bundle: SkillBundle): Skill {
        val manifest = SkillParser.parse(bundle.skillMd, bundle.fallbackName)
        val id = SkillParser.idFor(manifest.name)
        val files = bundle.files.entries
            .filter { it.value.size <= SkillArchive.MAX_FILE_BYTES }
            .take(SkillArchive.MAX_EXTRA_FILES)

        root.mkdirs()
        val staging = File(root, ".$id.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            File(staging, SkillParser.FILE_NAME).writeText(bundle.skillMd)
            for ((path, bytes) in files) {
                val target = resolveInside(staging, path) ?: throw SkillFormatException("Unsafe file path: $path")
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            val dir = File(root, id)
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) throw SkillFormatException("Could not save the skill")
        } finally {
            staging.deleteRecursively()
        }
        cache = null
        return list().first { it.id == id }
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val state = readState()
        val disabled = state.disabled.toMutableSet()
        if (enabled) disabled -= id else disabled += id
        writeState(state.copy(disabled = disabled))
    }

    @Synchronized
    fun delete(id: String) {
        if (id.isEmpty() || resolveInside(root, id)?.deleteRecursively() != true) return
        val state = readState()
        writeState(state.copy(disabled = state.disabled - id))
    }

    /** The text of an extra file of skill [id], or null when there is no such file. */
    fun readFile(id: String, path: String): String? {
        val skill = get(id) ?: return null
        val known = skill.files.find { it.equals(path.trim().removePrefix("./"), ignoreCase = true) } ?: return null
        return resolveInside(File(root, id), known)?.takeIf { it.isFile }?.readText()
    }

    /** Hash of the bundled version this skill was installed from; null for skills the user added. */
    @Synchronized
    internal fun bundledHash(id: String): String? = readState().bundled[id]

    @Synchronized
    internal fun recordBundled(id: String, hash: String) {
        val state = readState()
        writeState(state.copy(bundled = state.bundled + (id to hash)))
    }

    /** Drops the records of bundled skills that are no longer installed, so they can be installed again. */
    @Synchronized
    internal fun forgetDeletedBundled() {
        val state = readState()
        writeState(state.copy(bundled = state.bundled.filterKeys { File(root, it).isDirectory }))
    }

    internal fun installedHash(id: String): String? =
        File(File(root, id), SkillParser.FILE_NAME).takeIf { it.isFile }?.let { hash(it.readText()) }

    private fun load(): List<Skill> {
        val state = readState()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val text = File(dir, SkillParser.FILE_NAME).takeIf { it.isFile }?.readText() ?: return@mapNotNull null
                val manifest = runCatching { SkillParser.parse(text, dir.name) }.getOrNull() ?: return@mapNotNull null
                Skill(
                    id = dir.name,
                    name = manifest.name,
                    description = manifest.description,
                    tools = manifest.tools,
                    language = manifest.language,
                    instructions = manifest.instructions,
                    files = dir.walkTopDown().filter { it.isFile && it.name != SkillParser.FILE_NAME }
                        .map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList(),
                    enabled = dir.name !in state.disabled,
                    builtIn = state.bundled[dir.name] == hash(text),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private data class State(val disabled: Set<String>, val bundled: Map<String, String>)

    private fun readState(): State {
        val json = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        val disabled = json.optJSONArray("disabled") ?: JSONArray()
        val bundled = json.optJSONObject("bundled") ?: JSONObject()
        return State(
            disabled = (0 until disabled.length()).mapTo(HashSet()) { disabled.getString(it) },
            bundled = bundled.keys().asSequence().associateWith { bundled.getString(it) },
        )
    }

    private fun writeState(state: State) {
        root.mkdirs()
        val json = JSONObject()
            .put("disabled", JSONArray(state.disabled.sorted()))
            .put("bundled", JSONObject(state.bundled))
        stateFile.writeText(json.toString())
        cache = null
    }

    /** [path] under [dir], or null when it would land outside of it. */
    private fun resolveInside(dir: File, path: String): File? {
        val target = File(dir, path).canonicalFile
        return target.takeIf { it.path.startsWith(dir.canonicalPath + File.separator) }
    }

    internal companion object {
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
