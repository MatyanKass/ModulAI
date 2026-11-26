package com.modulai.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.modulai.app.audio.AudioHub
import com.modulai.app.code.CodeController
import com.modulai.app.skills.Extensions
import com.modulai.core.agent.HttpWeb
import com.modulai.core.agent.ResearchDepth
import com.modulai.core.agent.Web
import com.modulai.core.characters.CharacterStore
import com.modulai.core.device.DeviceInspector
import com.modulai.core.models.ModelStore
import com.modulai.core.runtime.Effort
import com.modulai.core.runtime.EngineRegistry
import com.modulai.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.settings by preferencesDataStore("settings")

/** App-wide singletons. Engines register themselves here as they are added. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engines = EngineRegistry().apply { register(LlamaCppEngine(context)) }
    val device = DeviceInspector(context)
    val models = ModelStore(context)
    val chats = ChatStore(java.io.File(context.filesDir, "chats"))
    val web: Web = HttpWeb()
    val characters = CharacterStore(context)
    val extensions = Extensions.of(context)
    val audio = AudioHub.get(context)

    val ageConfirmed: Flow<Boolean> = context.settings.data.map { it[AGE_CONFIRMED] ?: false }

    val runSettings = context.settings.data.map { p ->
        val d = RunSettings()
        RunSettings(
            preset = p[PRESET]?.let { name -> Preset.entries.find { it.name == name } } ?: d.preset,
            contextTokens = p[CONTEXT] ?: d.contextTokens,
            effort = p[EFFORT]?.let { name -> Effort.entries.find { it.name == name } } ?: d.effort,
            threads = p[THREADS] ?: defaultThreads(),
            lockMemory = p[MLOCK] ?: d.lockMemory,
            useMmap = p[MMAP] ?: d.useMmap,
            temperature = p[TEMPERATURE] ?: d.temperature,
            systemPrompt = p[SYSTEM_PROMPT] ?: d.systemPrompt,
            webTools = p[WEB_TOOLS] ?: d.webTools,
            rpEmoji = p[RP_EMOJI] ?: d.rpEmoji,
            autoMode = p[AUTO_MODE] ?: d.autoMode,
            researchDepth = p[RESEARCH_DEPTH]?.let { name -> ResearchDepth.entries.find { it.name == name } }
                ?: d.researchDepth,
        )
    }.stateIn(scope, SharingStarted.Eagerly, RunSettings(threads = defaultThreads()))

    val chat = ChatController(this)
    val research = ResearchController(this)
    val code = CodeController(this, java.io.File(context.filesDir, "projects"))
    val downloads = ModelDownloads(this)

    suspend fun confirmAge() {
        context.settings.edit { it[AGE_CONFIRMED] = true }
    }

    suspend fun save(s: RunSettings) {
        context.settings.edit {
            it[PRESET] = s.preset.name
            it[CONTEXT] = s.contextTokens
            it[EFFORT] = s.effort.name
            it[THREADS] = s.threads
            it[MLOCK] = s.lockMemory
            it[MMAP] = s.useMmap
            it[TEMPERATURE] = s.temperature
            it[SYSTEM_PROMPT] = s.systemPrompt
            it[WEB_TOOLS] = s.webTools
            it[RP_EMOJI] = s.rpEmoji
            it[AUTO_MODE] = s.autoMode
            it[RESEARCH_DEPTH] = s.researchDepth.name
        }
    }

    /**
     * Half the cores, at most four. Measured on a Snapdragon 8 Elite (2 + 6 cores):
     * 4 threads gave 45 tok/s, 6 gave 40, and 2 or 8 gave 28.
     */
    private fun defaultThreads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private companion object {
        val AGE_CONFIRMED = booleanPreferencesKey("age_confirmed")
        val PRESET = stringPreferencesKey("preset")
        val CONTEXT = intPreferencesKey("context")
        val EFFORT = stringPreferencesKey("effort")
        val THREADS = intPreferencesKey("threads")
        val MLOCK = booleanPreferencesKey("mlock")
        val MMAP = booleanPreferencesKey("mmap")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val WEB_TOOLS = booleanPreferencesKey("web_tools")
        val RP_EMOJI = booleanPreferencesKey("rp_emoji")
        val AUTO_MODE = booleanPreferencesKey("auto_mode")
        val RESEARCH_DEPTH = stringPreferencesKey("research_depth")
    }
}
