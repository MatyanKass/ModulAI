package com.modulai.core.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HfFile(val path: String, val sizeBytes: Long)

data class HfRepo(val id: String, val downloads: Long, val likes: Long)

/** A non-success HTTP answer; 401 and 403 from Hugging Face mean the model is gated or private. */
class HttpStatusException(val code: Int, message: String) : IOException(message) {
    val accessDenied get() = code == 401 || code == 403
}

object HfApi {
    /** GGUF files of [repo], smallest first. Split shards other than the first are skipped. */
    suspend fun listGgufFiles(repo: String, token: String? = null): List<HfFile> =
        get("https://huggingface.co/api/models/$repo/tree/main?recursive=true", token, ::parseTree)

    /** Repositories with GGUF files matching [query], most downloaded first. */
    suspend fun searchGguf(query: String, token: String? = null): List<HfRepo> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return get(
            "https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=30",
            token,
            ::parseSearch,
        )
    }

    private suspend fun <T> get(url: String, token: String?, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code != 200) throw HttpStatusException(code, "Hugging Face returned HTTP $code")
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: JSONException) {
            throw IOException("Unexpected answer from Hugging Face", e)
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

    internal fun parseSearch(json: String): List<HfRepo> {
        val array = JSONArray(json)
        val repos = ArrayList<HfRepo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            repos += HfRepo(id, item.optLong("downloads"), item.optLong("likes"))
        }
        return repos
    }
}
