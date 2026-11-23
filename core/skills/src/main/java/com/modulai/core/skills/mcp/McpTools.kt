package com.modulai.core.skills.mcp

import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.Tool
import com.modulai.core.runtime.ToolSpec
import org.json.JSONObject

/** A tool of a remote MCP server, callable by the chat model under [exposedName]. */
class McpToolAdapter(
    private val client: McpClient,
    private val tool: McpTool,
    exposedName: String,
    private val maxResultChars: Int,
) : Tool {
    override val spec = ToolSpec(exposedName, shorten(tool.description), tool.inputSchema)

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val text = McpResults.flatten(client.callTool(tool, args), maxResultChars)
        log.add(LogKind.TOOL_RESULT, spec.name, text)
        return text
    }

    private companion object {
        const val MAX_DESCRIPTION = 300

        fun shorten(text: String): String {
            val flat = text.replace(Regex("""\s+"""), " ").trim()
            return if (flat.length <= MAX_DESCRIPTION) flat else flat.take(MAX_DESCRIPTION - 1) + "…"
        }
    }
}

private const val MAX_NAME = 64

/**
 * Wraps [tools] of the server [serverId] for the chat model. Names get the server id as a prefix so
 * that two servers (or a server and a built-in tool) cannot clash, and are reduced to characters
 * every chat template and grammar accepts. At most [limit] tools are exposed: each one costs context.
 */
fun mcpTools(
    serverId: String,
    client: McpClient,
    tools: List<McpTool>,
    maxResultChars: Int = 6000,
    limit: Int = 32,
): List<Tool> {
    val prefix = McpStore.sanitizeId(serverId).ifEmpty { "mcp" }
    val taken = HashSet<String>()
    return tools.take(limit).map { tool ->
        val base = "${prefix}_${tool.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}".take(MAX_NAME)
        val name = generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "${base.take(MAX_NAME - 3)}_$it" }
            .first { taken.add(it) }
        McpToolAdapter(client, tool, name, maxResultChars)
    }
}
