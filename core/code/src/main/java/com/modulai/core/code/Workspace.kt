package com.modulai.core.code

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Why a workspace operation was refused; the UI maps it to a localized message. */
enum class Refusal {
    INVALID_NAME, NAME_TAKEN, INVALID_PATH, OUTSIDE_PROJECT, NOT_FOUND, IS_DIRECTORY, ALREADY_EXISTS,
    FILE_TOO_LARGE, PROJECT_FULL, TOO_MANY_FILES, BINARY, BAD_ARCHIVE,
}

/** The message is written for the model: it says what went wrong and what to do instead. */
class WorkspaceException(val refusal: Refusal, message: String) : IOException(message)

data class Limits(
    val maxFileBytes: Long = 2L * 1024 * 1024,
    val maxProjectBytes: Long = 32L * 1024 * 1024,
    val maxFiles: Int = 1000,
)

data class Project(val id: String, val name: String, val updatedAt: Long)

/** A file of a project; [path] is relative to the project root and uses forward slashes. */
data class FileEntry(val path: String, val size: Long)

/**
 * Projects on disk. Each one is a directory under [root]: `<id>/files` holds the project itself,
 * and whatever the app keeps about it (name, conversation, backups) lives next to it, out of the model's reach.
 */
class Workspace(private val root: File, val limits: Limits = Limits()) {
    private val logs = HashMap<String, ChangeLog>()

    init {
        root.mkdirs()
    }

    /** Most recently changed first. */
    fun projects(): List<Project> = root.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { dir -> runCatching { read(dir) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun find(id: String): Project? = projects().find { it.id == id }

    fun findByName(name: String): Project? = projects().find { it.name.equals(name.trim(), ignoreCase = true) }

    /** A taken name gets a number appended, so creating never fails on a clash. */
    fun create(name: String): Project {
        val base = cleanName(name)
        val taken = projects().map { it.name.lowercase() }.toSet()
        val unique = generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base $it" }
            .first { it.lowercase() !in taken }
        val id = UUID.randomUUID().toString()
        File(home(id), FILES).mkdirs()
        writeMeta(id, unique)
        return Project(id, unique, meta(id).lastModified())
    }

    fun rename(id: String, name: String): Project {
        val clean = cleanName(name)
        val current = find(id) ?: throw WorkspaceException(Refusal.NOT_FOUND, "No such project")
        if (projects().any { it.id != id && it.name.equals(clean, ignoreCase = true) }) {
            throw WorkspaceException(Refusal.NAME_TAKEN, "A project named \"$clean\" already exists")
        }
        writeMeta(id, clean)
        return current.copy(name = clean, updatedAt = meta(id).lastModified())
    }

    fun delete(id: String) {
        synchronized(logs) { logs.remove(id) }
        home(id).deleteRecursively()
    }

    fun open(id: String): ProjectDir {
        val meta = meta(id)
        if (!meta.isFile) throw WorkspaceException(Refusal.NOT_FOUND, "No such project")
        return ProjectDir(id, File(home(id), FILES), limits) { meta.setLastModified(System.currentTimeMillis()) }
    }

    /** The record of the project's last run. One instance per project, so every user of it sees the same run. */
    fun changes(id: String): ChangeLog = synchronized(logs) { logs.getOrPut(id) { ChangeLog(sidecar(id, "changes")) } }

    /** A file or directory the app keeps about the project, outside of the project's own files. */
    fun sidecar(id: String, name: String): File = File(home(id), name)

    private fun home(id: String): File {
        // Ids come back from the UI and from saved state; never let one address another directory.
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' }) {
            throw WorkspaceException(Refusal.NOT_FOUND, "No such project")
        }
        return File(root, id)
    }

    private fun meta(id: String) = File(home(id), META)

    private fun writeMeta(id: String, name: String) {
        val tmp = File(home(id), "$META.tmp")
        tmp.writeText(JSONObject().put("name", name).toString())
        tmp.renameTo(meta(id))
    }

    private fun read(dir: File): Project {
        val meta = File(dir, META)
        return Project(dir.name, JSONObject(meta.readText()).getString("name"), meta.lastModified())
    }

    private fun cleanName(name: String): String {
        val clean = name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()
        if (clean.isEmpty()) throw WorkspaceException(Refusal.INVALID_NAME, "The project name is empty")
        return clean
    }

    private companion object {
        const val FILES = "files"
        const val META = "project.json"
        const val MAX_NAME = 60
    }
}

/** The files of one project. Every path is untrusted and is confined to [dir]. */
class ProjectDir internal constructor(
    val id: String,
    dir: File,
    private val limits: Limits,
    private val touch: () -> Unit,
) {
    val dir: File = dir.canonicalFile

    /**
     * Maps a project-relative path to a file inside the project.
     * Symbolic links are followed before the check, so a link pointing outside is rejected too.
     */
    fun resolve(path: String): File {
        val normal = path.trim().replace('\\', '/')
        if (normal.isEmpty() || '\u0000' in normal) throw WorkspaceException(Refusal.INVALID_PATH, "The path is empty")
        if (normal.startsWith("/") || normal.startsWith("~") || ABSOLUTE_DRIVE.containsMatchIn(normal)) {
            throw WorkspaceException(
                Refusal.OUTSIDE_PROJECT,
                "\"$path\" is an absolute path. Use a path relative to the project, like src/main.js",
            )
        }
        val parts = normal.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) throw WorkspaceException(Refusal.INVALID_PATH, "\"$path\" is not a file path")
        if (parts.any { it == ".." }) {
            throw WorkspaceException(Refusal.OUTSIDE_PROJECT, "\"$path\" leaves the project. \"..\" is not allowed")
        }
        if (parts.size > MAX_DEPTH || parts.any { it.length > MAX_SEGMENT }) {
            throw WorkspaceException(Refusal.INVALID_PATH, "\"$path\" is too long or too deep")
        }
        val file = File(dir, parts.joinToString(File.separator)).canonicalFile
        if (!file.path.startsWith(dir.path + File.separator)) {
            throw WorkspaceException(Refusal.OUTSIDE_PROJECT, "\"$path\" leaves the project")
        }
        return file
    }

    /** The path as the project knows it: relative, forward slashes, no `./`. */
    fun normalize(path: String): String = resolve(path).relativeTo(dir).invariantSeparatorsPath

    fun exists(path: String): Boolean = resolve(path).isFile

    /** All files, sorted by path. Symbolic links are not followed. */
    fun files(): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        fun walk(d: File, prefix: String) {
            for (f in d.listFiles().orEmpty()) {
                if (Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) walk(f, "$prefix${f.name}/") else out += FileEntry(prefix + f.name, f.length())
            }
        }
        walk(dir, "")
        return out.sortedBy { it.path }
    }

    fun readBytes(path: String): ByteArray = existing(path).readBytes()

    /** Reads a text file; refuses binary content, which would only be noise for the model or the editor. */
    fun read(path: String): String {
        val bytes = readBytes(path)
        if (isBinary(bytes)) throw WorkspaceException(Refusal.BINARY, "\"$path\" is a binary file and cannot be shown as text")
        return String(bytes, Charsets.UTF_8)
    }

    fun write(path: String, text: String) = writeBytes(path, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(path: String, bytes: ByteArray) = writeStream(path, bytes.inputStream())

    /**
     * Stores [input] at [path], counting the bytes actually read: sizes declared elsewhere
     * (a ZIP header, for one) are not trusted.
     */
    fun writeStream(path: String, input: InputStream) {
        val file = resolve(path)
        if (file.isDirectory) throw WorkspaceException(Refusal.IS_DIRECTORY, "\"$path\" is a folder, not a file")
        val all = files()
        val old = if (file.isFile) file.length() else -1L
        if (old < 0 && all.size >= limits.maxFiles) {
            throw WorkspaceException(Refusal.TOO_MANY_FILES, "The project already has ${limits.maxFiles} files, which is the limit")
        }
        val room = limits.maxProjectBytes - all.sumOf { it.size } + old.coerceAtLeast(0)
        val parent = file.parentFile ?: dir
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw WorkspaceException(Refusal.INVALID_PATH, "Cannot create the folder for \"$path\": a file is in the way")
        }
        // Written aside first so a refused or interrupted write leaves the old content intact.
        val tmp = File.createTempFile(".write", null, parent)
        try {
            var total = 0L
            tmp.outputStream().use { out ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limits.maxFileBytes) {
                        throw WorkspaceException(
                            Refusal.FILE_TOO_LARGE,
                            "\"$path\" is larger than the limit of ${limits.maxFileBytes / 1024} KB per file",
                        )
                    }
                    if (total > room) {
                        throw WorkspaceException(
                            Refusal.PROJECT_FULL,
                            "The project would exceed its limit of ${limits.maxProjectBytes / 1024} KB",
                        )
                    }
                    out.write(buffer, 0, n)
                }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
            pruneEmpty(parent)
        }
        touch()
    }

    fun delete(path: String) {
        val file = existing(path)
        file.delete()
        pruneEmpty(file.parentFile)
        touch()
    }

    fun move(from: String, to: String) {
        val source = existing(from)
        val target = resolve(to)
        if (target.exists()) throw WorkspaceException(Refusal.ALREADY_EXISTS, "\"$to\" already exists")
        val parent = target.parentFile ?: dir
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw WorkspaceException(Refusal.INVALID_PATH, "Cannot create the folder for \"$to\": a file is in the way")
        }
        Files.move(source.toPath(), target.toPath())
        pruneEmpty(source.parentFile)
        touch()
    }

    private fun existing(path: String): File {
        val file = resolve(path)
        if (file.isDirectory) throw WorkspaceException(Refusal.IS_DIRECTORY, "\"$path\" is a folder, not a file")
        if (!file.isFile) throw WorkspaceException(Refusal.NOT_FOUND, "\"$path\" does not exist. Call list_files to see the files")
        return file
    }

    /** Folders exist only to hold files, so the ones left empty go away. */
    private fun pruneEmpty(start: File?) {
        var d = start
        while (d != null && d != dir && d.list()?.isEmpty() == true) {
            d.delete()
            d = d.parentFile
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_SEGMENT = 120
        val ABSOLUTE_DRIVE = Regex("^[A-Za-z]:")
    }
}

internal fun isBinary(bytes: ByteArray): Boolean {
    for (i in 0 until minOf(bytes.size, 8000)) if (bytes[i] == 0.toByte()) return true
    return false
}
