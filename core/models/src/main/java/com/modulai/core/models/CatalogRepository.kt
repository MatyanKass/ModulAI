package com.modulai.core.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The model catalog: the copy shipped in the APK, superseded by a newer one fetched from GitHub. */
class CatalogRepository(context: Context) {
    private val assets = context.applicationContext.assets
    private val cache = File(context.filesDir, "catalog-models.json")

    suspend fun load(): Catalog = withContext(Dispatchers.IO) {
        Catalog.newest(bundled(), readOrNull { cache.readText() }?.let(Catalog::parse))
    }

    /** Fetches the catalog from GitHub unless that was done recently. Never fails; returns [load]. */
    suspend fun refresh(): Catalog = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - cache.lastModified() > REFRESH_INTERVAL_MS) {
            val text = readOrNull { fetch() }
            val remote = text?.let(Catalog::parse)
            if (remote != null && remote.version >= bundled().version) {
                val tmp = File(cache.parentFile, cache.name + ".tmp")
                readOrNull {
                    tmp.writeText(text)
                    tmp.renameTo(cache)
                }
            }
        }
        load()
    }

    private fun bundled(): Catalog =
        readOrNull { assets.open(ASSET).bufferedReader().use { it.readText() } }?.let(Catalog::parse) ?: Catalog.EMPTY

    private fun fetch(): String {
        val conn = URL(REMOTE_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <T> readOrNull(block: () -> T): T? = try {
        block()
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val ASSET = "models.json"
        const val REMOTE_URL = "https://raw.githubusercontent.com/MatyanKass/ModulAI/main/catalog/models.json"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
