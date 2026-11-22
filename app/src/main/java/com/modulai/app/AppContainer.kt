package com.modulai.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.modulai.core.device.DeviceInspector
import com.modulai.core.runtime.EngineRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore("settings")

/** App-wide singletons. Engines register themselves here as they are added. */
class AppContainer(private val context: Context) {
    val engines = EngineRegistry()
    val device = DeviceInspector(context)

    val ageConfirmed: Flow<Boolean> = context.settings.data.map { it[AGE_CONFIRMED] ?: false }

    suspend fun confirmAge() {
        context.settings.edit { it[AGE_CONFIRMED] = true }
    }

    private companion object {
        val AGE_CONFIRMED = booleanPreferencesKey("age_confirmed")
    }
}
