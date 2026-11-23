package com.modulai.core.code

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ChangeKind { CREATED, MODIFIED, DELETED }

/** What a run did to one file. [before] and [after] are empty when the file was absent or is [binary]. */
data class FileChange(
    val path: String,
    val kind: ChangeKind,
    val before: String,
    val after: String,
    val binary: Boolean = false,
)

/**
 * Remembers what every file looked like before the current run touched it, so the run can be
 * shown as a diff and undone file by file. The originals are kept in [dir], which survives a restart.
 */
class ChangeLog(private val dir: File) {
    /** Path to the backup file name, or null when the file did not exist before the run. */
    private val originals = LinkedHashMap<String, String?>()

    init {
        runCatching {
            val entries = JSONObject(File(dir, INDEX).readText()).getJSONArray("files")
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                originals[e.getString("path")] = if (e.isNull("backup")) null else e.getString("backup")
            }
        }
    }

    /** Starts a new run: the previous one can no longer be reverted. */
    @Synchronized
    fun begin() {
        dir.deleteRecursively()
        originals.clear()
    }

    /** Must be called before [path] is changed. Only the first call per run and file keeps anything. */
    @Synchronized
    fun record(project: ProjectDir, path: String) {
        val key = project.normalize(path)
        if (key in originals) return
        dir.mkdirs()
        val backup = if (project.exists(key)) {
            originals.size.toString().also { File(dir, it).writeBytes(project.readBytes(key)) }
        } else {
            null
        }
        originals[key] = backup
        val files = JSONArray(originals.map { (p, b) -> JSONObject().put("path", p).put("backup", b ?: JSONObject.NULL) })
        File(dir, INDEX).writeText(JSONObject().put("files", files).toString())
    }

    /** Files that differ from their state before the run, sorted by path. */
    @Synchronized
    fun changes(project: ProjectDir): List<FileChange> = originals.mapNotNull { (path, backup) ->
        val before = backup?.let { File(dir, it).readBytes() }
        val after = if (project.exists(path)) project.readBytes(path) else null
        if (before == null && after == null || before != null && after != null && before.contentEquals(after)) {
            return@mapNotNull null
        }
        val kind = when {
            before == null -> ChangeKind.CREATED
            after == null -> ChangeKind.DELETED
            else -> ChangeKind.MODIFIED
        }
        if (before?.let(::isBinary) == true || after?.let(::isBinary) == true) {
            FileChange(path, kind, "", "", binary = true)
        } else {
            FileChange(path, kind, before?.toString(Charsets.UTF_8).orEmpty(), after?.toString(Charsets.UTF_8).orEmpty())
        }
    }.sortedBy { it.path }

    /** Puts [path] back to what it was before the run; a file the run created is deleted. */
    @Synchronized
    fun revert(project: ProjectDir, path: String) {
        val key = project.normalize(path)
        if (key !in originals) throw WorkspaceException(Refusal.NOT_FOUND, "\"$path\" was not changed in the last run")
        val backup = originals[key]
        if (backup != null) {
            project.writeBytes(key, File(dir, backup).readBytes())
        } else if (project.exists(key)) {
            project.delete(key)
        }
    }

    private companion object {
        const val INDEX = "index.json"
    }
}
