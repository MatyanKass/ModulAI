package com.modulai.core.code

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Writes all files of the project to [out] as a ZIP archive. [out] is left open. */
fun ProjectDir.exportZip(out: OutputStream) {
    val zip = ZipOutputStream(out)
    for (file in files()) {
        zip.putNextEntry(ZipEntry(file.path))
        resolve(file.path).inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
    zip.finish()
}

/**
 * Unpacks a ZIP archive into a new project named [name].
 * Entry names are untrusted: each goes through [ProjectDir.resolve], so an entry cannot land outside the
 * project, and the workspace limits apply to the unpacked bytes. A refused archive leaves no project behind.
 */
fun Workspace.importZip(name: String, input: InputStream): Project {
    val project = create(name)
    try {
        val dir = open(project.id)
        var entries = 0
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            // Counted here as well: entries that overwrite each other would never reach the file limit.
            if (++entries > limits.maxFiles) {
                throw WorkspaceException(Refusal.TOO_MANY_FILES, "The archive has more than ${limits.maxFiles} files")
            }
            dir.writeStream(entry.name, zip)
        }
        if (entries == 0) throw WorkspaceException(Refusal.BAD_ARCHIVE, "The archive is empty or is not a ZIP file")
        hoistSingleFolder(dir)
        return project
    } catch (e: Exception) {
        delete(project.id)
        throw if (e is ZipException) WorkspaceException(Refusal.BAD_ARCHIVE, "The archive is damaged: ${e.message}") else e
    }
}

/** Archives made from a folder wrap everything in it; the project should start at its content. */
private fun hoistSingleFolder(dir: ProjectDir) {
    val files = dir.files()
    val top = files.first().path.substringBefore('/', "")
    if (top.isEmpty() || files.any { !it.path.startsWith("$top/") }) return
    // An entry named like the folder itself could not move up while the folder is still there.
    if (files.any { it.path == "$top/$top" || it.path.startsWith("$top/$top/") }) return
    for (file in files) dir.move(file.path, file.path.removePrefix("$top/"))
}
