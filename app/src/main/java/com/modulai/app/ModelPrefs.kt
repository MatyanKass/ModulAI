package com.modulai.app

import android.content.Context

/** Small settings of the Models tab. Kept apart from the main settings store, which AppContainer owns. */
class ModelPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("models", Context.MODE_PRIVATE)

    /** Hugging Face access token for gated models, or null. Must never be logged. */
    var hfToken: String?
        get() = prefs.getString(HF_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(HF_TOKEN) else putString(HF_TOKEN, value.trim())
        }.apply()

    var showUncensored: Boolean
        get() = prefs.getBoolean(SHOW_UNCENSORED, false)
        set(value) = prefs.edit().putBoolean(SHOW_UNCENSORED, value).apply()

    /** The notification permission is asked for once; after that the user's answer stands. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit().putBoolean(NOTIFICATIONS_ASKED, value).apply()

    private companion object {
        const val HF_TOKEN = "hf_token"
        const val SHOW_UNCENSORED = "show_uncensored"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
