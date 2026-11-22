package com.modulai.core.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HfFile(val path: String, val sizeBytes: Long)

object HfApi {
    /** GGUF files of [repo], smallest first. Split shards other than the first are skipped. */
    suspend fun listGgufFiles(repo: String, token: String? = null): List<HfFile> = withContext(Dispatchers.IO) {
        val conn = URL("https://huggingface.co/api/models/$repo/tree/main?recursive=true")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode != 200) throw IOException("Hugging Face returned ${conn.responseCode} for $repo")
            parseTree(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseTree(json: String): List<HfFile> {
        val array = JSONArray(json)
        val files = ArrayList<HfFile>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optString("type") != "file") continue
            val path = item.getString("path")
            if (!path.endsWith(".gguf", ignoreCase = true)) continue
            // Vision projectors are companions of a model, not chat models themselves.
            if (path.substringAfterLast('/').startsWith("mmproj", ignoreCase = true)) continue
            val size = item.optJSONObject("lfs")?.optLong("size") ?: item.optLong("size")
            files += HfFile(path, size)
        }
        return files.sortedBy { it.sizeBytes }
    }
}
