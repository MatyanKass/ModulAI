package com.modulai.app.skills

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import com.modulai.core.agent.Tool
import com.modulai.core.skills.BuiltInSkills
import com.modulai.core.skills.Skill
import com.modulai.core.skills.SkillAssets
import com.modulai.core.skills.SkillInstaller
import com.modulai.core.skills.SkillStore
import com.modulai.core.skills.mcp.McpClient
import com.modulai.core.skills.mcp.McpServerConfig
import com.modulai.core.skills.mcp.McpStore
import com.modulai.core.skills.mcp.McpTool
import com.modulai.core.skills.mcp.mcpTools
import com.modulai.core.skills.skillTools
import com.modulai.core.skills.skillsPromptSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

enum class McpState { CONNECTING, CONNECTED, FAILED }

data class McpStatus(
    val state: McpState,
    /** Tool names as the server calls them. */
    val tools: List<String> = emptyList(),
    val error: String? = null,
    val protocol: String? = null,
)

/**
 * What the user added to the chat model: skills and MCP servers.
 *
 * For the chat: call [tools] when assembling the tools of a turn and append [promptSection] to the
 * system prompt. [tools] loads the state on first use, so call it before [promptSection]; after
 * that both always describe the same set of skills.
 *
 * The app shares one instance, [Extensions.of]. The other constructor takes explicit folders:
 * [skillsRoot] for installed skills, [mcpRoot] for the server list (it holds the servers' secrets
 * and must be app-private), and [assets] with the bundled skills as top-level folders, or null.
 */
class Extensions(
    skillsRoot: File,
    mcpRoot: File,
    private val assets: SkillAssets? = null,
    private val clientVersion: String = "1.0",
) {
    constructor(context: Context) : this(
        File(context.filesDir, "skills"),
        File(context.filesDir, "mcp"),
        AndroidSkillAssets(context.applicationContext.assets),
        appVersion(context),
    )

    private val skillStore = SkillStore(skillsRoot)
    private val serverStore = McpStore(mcpRoot)
    private val installer = SkillInstaller(skillStore)

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers

    private val _status = MutableStateFlow<Map<String, McpStatus>>(emptyMap())

    /** Connection state by server id; a server that was not contacted yet has no entry. */
    val status: StateFlow<Map<String, McpStatus>> = _status

    private class Connection(val config: McpServerConfig, val client: McpClient, val tools: List<McpTool>?, val at: Long)

    private val connections = HashMap<String, Connection>()
    private val connecting = Mutex()
    private val loading = Mutex()
    private var loaded = false

    /** Reads the stored state; on the first call also installs the bundled skills that are missing. */
    suspend fun load() = loading.withLock {
        if (loaded) return@withLock
        withContext(Dispatchers.IO) {
            assets?.let { runCatching { BuiltInSkills.installMissing(it, skillStore) } }
            _skills.value = skillStore.list()
            _servers.value = serverStore.list()
        }
        loaded = true
    }

    /**
     * The tools the extensions add to a chat turn: `use_skill` (and `read_skill_file`) when skills
     * are enabled, plus the tools of every enabled MCP server. Servers are contacted on first use
     * and their tool lists are cached; one that fails is skipped and tried again later.
     * [maxResultChars] bounds what a single call may put into the context.
     */
    suspend fun tools(maxResultChars: Int = 6000): List<Tool> {
        load()
        val skillTools = skillTools(skillStore, ::enabledSkills, maxResultChars)
        val mcp = connecting.withLock {
            coroutineScope {
                _servers.value.filter { it.enabled }.map { config ->
                    async {
                        val connection = connect(config)
                        mcpTools(config.id, connection.client, connection.tools.orEmpty(), maxResultChars)
                    }
                }.awaitAll().flatten()
            }
        }
        return skillTools + mcp
    }

    /** The system prompt section that lists the enabled skills; empty when there are none. */
    fun promptSection(): String = skillsPromptSection(enabledSkills())

    private fun enabledSkills() = _skills.value.filter { it.enabled }

    private suspend fun connect(config: McpServerConfig): Connection {
        val now = System.currentTimeMillis()
        val cached = synchronized(connections) { connections[config.id] }
        if (cached != null && cached.config == config && now - cached.at < (if (cached.tools != null) REFRESH_MS else RETRY_MS)) {
            return cached
        }
        setStatus(config.id, McpStatus(McpState.CONNECTING))
        val client = McpClient(config, clientVersion = clientVersion)
        val result = try {
            probe(client)
        } catch (e: CancellationException) {
            // Whoever asked went away; "connecting" must not stay on screen forever.
            _status.update { it - config.id }
            throw e
        }
        setStatus(config.id, result.second)
        return Connection(config, client, result.first, now).also { synchronized(connections) { connections[config.id] = it } }
    }

    private suspend fun probe(client: McpClient): Pair<List<McpTool>?, McpStatus> = try {
        val tools = client.listTools()
        tools to McpStatus(McpState.CONNECTED, tools.map { it.name }, protocol = client.protocolVersion)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything a server manages to break must stay inside its own entry.
        null to McpStatus(McpState.FAILED, error = e.message ?: e.toString())
    }

    private fun setStatus(id: String, status: McpStatus) = _status.update { it + (id to status) }

    /** Contacts the enabled servers that have no fresh connection, so that the screen can show their state. */
    suspend fun connectServers() {
        load()
        connecting.withLock {
            coroutineScope { _servers.value.filter { it.enabled }.map { async { connect(it) } }.awaitAll() }
        }
    }

    /** Tries [config] without saving it: the handshake and the tool list. */
    suspend fun test(config: McpServerConfig): McpStatus = probe(McpClient(config, clientVersion = clientVersion)).second

    suspend fun installSkillFromLink(link: String): Skill = installer.fromLink(link).also { refreshSkills() }

    /** Installs a SKILL.md or a ZIP archive read from [stream]. */
    suspend fun installSkill(stream: InputStream, fallbackName: String): Skill =
        withContext(Dispatchers.IO) { skillStore.install(stream, fallbackName) }.also { refreshSkills() }

    suspend fun setSkillEnabled(id: String, enabled: Boolean) {
        withContext(Dispatchers.IO) { skillStore.setEnabled(id, enabled) }
        refreshSkills()
    }

    suspend fun deleteSkill(id: String) {
        withContext(Dispatchers.IO) { skillStore.delete(id) }
        refreshSkills()
    }

    /** Reinstalls the bundled skills the user deleted. */
    suspend fun restoreBuiltInSkills() {
        withContext(Dispatchers.IO) { assets?.let { BuiltInSkills.restore(it, skillStore) } }
        refreshSkills()
    }

    /** Adds or updates a server; a blank id means a new one. The saved copy carries the final id. */
    suspend fun saveServer(config: McpServerConfig): McpServerConfig {
        val saved = withContext(Dispatchers.IO) { serverStore.save(config) }
        forget(saved.id)
        refreshServers()
        return saved
    }

    suspend fun deleteServer(id: String) {
        withContext(Dispatchers.IO) { serverStore.delete(id) }
        forget(id)
        refreshServers()
    }

    private fun forget(id: String) {
        synchronized(connections) { connections.remove(id) }
        _status.update { it - id }
    }

    private suspend fun refreshSkills() {
        _skills.value = withContext(Dispatchers.IO) { skillStore.list() }
    }

    private suspend fun refreshServers() {
        _servers.value = withContext(Dispatchers.IO) { serverStore.list() }
    }

    companion object {
        private const val REFRESH_MS = 15 * 60_000L
        private const val RETRY_MS = 60_000L

        @Volatile private var instance: Extensions? = null

        /** The instance shared by the chat and the settings screen. */
        fun of(context: Context): Extensions = instance ?: synchronized(this) {
            instance ?: Extensions(context.applicationContext).also { instance = it }
        }
    }
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
} catch (e: PackageManager.NameNotFoundException) {
    "1.0"
}

private class AndroidSkillAssets(private val assets: AssetManager) : SkillAssets {
    override fun list(path: String): List<String> = assets.list(path).orEmpty().toList()
    override fun open(path: String): InputStream = assets.open(path)
}
