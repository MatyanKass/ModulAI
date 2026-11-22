package com.modulai.core.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Models on disk. Until the catalog lands, the directory listing is the registry. */
class ModelStore(private val context: Context) {
    val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    fun list(): List<ModelDescriptor> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { it.toDescriptor() }

    fun fileFor(name: String) = File(dir, sanitize(name))

    fun delete(model: ModelDescriptor) {
        File(model.path).delete()
    }

    /** Copies a file picked through the system picker; llama.cpp needs a real path to mmap. */
    suspend fun import(uri: Uri): ModelDescriptor = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "model.gguf"
        if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("Only .gguf files are supported for now")
        val target = fileFor(name)
        val part = File(dir, target.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { input.copyTo(it, 1 shl 20) }
        } ?: throw IOException("Could not open $name")
        if (!part.renameTo(target)) throw IOException("Could not store $name")
        target.toDescriptor()
    }

    private fun File.toDescriptor() =
        ModelDescriptor(nameWithoutExtension, absolutePath, ModelFormat.GGUF, Capability.TEXT_GENERATION, length())

    companion object {
        fun sanitize(name: String) = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").trimStart('.')
    }
}
