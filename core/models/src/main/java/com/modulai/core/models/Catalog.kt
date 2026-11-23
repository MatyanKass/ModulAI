package com.modulai.core.models

import org.json.JSONException
import org.json.JSONObject

enum class ModelCategory(val key: String) {
    CHAT("chat"), RP("rp"), RESEARCH("research"), CODE("code"),

    /** A category this app version does not know yet. */
    OTHER("");

    companion object {
        fun fromKey(key: String) = entries.find { it.key == key } ?: OTHER
    }
}

data class CatalogEntry(
    val id: String,
    val name: String,
    val category: ModelCategory,
    val repo: String,
    val file: String,
    val sizeBytes: Long,
    val params: String,
    val quant: String,
    val minRamGb: Int,
    val languages: List<String>,
    val uncensored: Boolean,
    val thinking: Boolean,
    val tools: Boolean,
    val description: Map<String, String>,
) {
    val link get() = HfLink.File(repo, "main", file)

    /** The description in [language], falling back to English and then to whatever exists. */
    fun describe(language: String): String =
        description[language] ?: description["en"] ?: description.values.firstOrNull().orEmpty()
}

data class Catalog(val version: Int, val models: List<CatalogEntry>) {
    companion object {
        val EMPTY = Catalog(0, emptyList())

        /**
         * Returns null when [json] is not a catalog at all. Entries without an id, repository
         * or GGUF file, and repeated ids, are skipped, so one bad record does not hide the rest.
         */
        fun parse(json: String): Catalog? = try {
            val root = JSONObject(json)
            val array = root.getJSONArray("models")
            val models = ArrayList<CatalogEntry>()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(::parseEntry)?.let(models::add)
            }
            Catalog(root.optInt("version", 1), models.distinctBy { it.id })
        } catch (e: JSONException) {
            null
        }

        /** The downloaded copy wins only when it is at least as new as the one shipped in the APK. */
        fun newest(bundled: Catalog, cached: Catalog?): Catalog =
            if (cached != null && cached.version >= bundled.version) cached else bundled

        private fun parseEntry(o: JSONObject): CatalogEntry? {
            val id = o.optString("id")
            val repo = o.optString("repo")
            val file = o.optString("file")
            if (id.isEmpty() || repo.isEmpty() || !file.endsWith(".gguf", ignoreCase = true)) return null

            val languages = o.optJSONArray("languages")
            val description = o.optJSONObject("description")
            return CatalogEntry(
                id = id,
                name = o.optString("name").ifEmpty { id },
                category = ModelCategory.fromKey(o.optString("category")),
                repo = repo,
                file = file,
                sizeBytes = o.optLong("sizeBytes"),
                params = o.optString("params"),
                quant = o.optString("quant"),
                minRamGb = o.optInt("minRamGb"),
                languages = if (languages == null) emptyList() else
                    (0 until languages.length()).map { languages.optString(it) }.filter { it.isNotEmpty() },
                uncensored = o.optBoolean("uncensored"),
                thinking = o.optBoolean("thinking"),
                tools = o.optBoolean("tools"),
                description = description?.keys()?.asSequence()?.associateWith { description.optString(it) }.orEmpty(),
            )
        }
    }
}
