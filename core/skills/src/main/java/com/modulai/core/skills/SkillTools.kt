package com.modulai.core.skills

import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.Tool
import com.modulai.core.runtime.ToolSpec
import org.json.JSONObject

private const val MAX_LISTED_SKILLS = 24
private const val MAX_LISTED_DESCRIPTION = 140

/**
 * The part of the system prompt that tells the model which skills exist. One line per skill:
 * the instructions themselves stay out of the context until the model asks for them with `use_skill`.
 * Empty when nothing is enabled.
 */
fun skillsPromptSection(enabled: List<Skill>): String {
    if (enabled.isEmpty()) return ""
    val lines = enabled.take(MAX_LISTED_SKILLS).joinToString("\n") { skill ->
        val description = skill.description.let { if (it.length > MAX_LISTED_DESCRIPTION) it.take(MAX_LISTED_DESCRIPTION - 1) + "…" else it }
        if (description.isEmpty()) "- ${skill.id}" else "- ${skill.id} — $description"
    }
    return "## Skills\n" +
        "You have skills: ready-made instructions for particular kinds of tasks. When a request matches one, " +
        "call use_skill with its name before answering and follow the instructions it returns.\n" + lines
}

/** The tools that go with [skillsPromptSection]. [skills] is asked on every call, so edits apply at once. */
fun skillTools(store: SkillStore, skills: () -> List<Skill>, maxChars: Int = 6000): List<Tool> {
    val enabled = skills()
    return when {
        enabled.isEmpty() -> emptyList()
        enabled.none { it.files.isNotEmpty() } -> listOf(UseSkillTool(skills, maxChars))
        else -> listOf(UseSkillTool(skills, maxChars), ReadSkillFileTool(store, skills, maxChars))
    }
}

private fun find(skills: List<Skill>, name: String): Skill? {
    val wanted = name.trim()
    return skills.find { it.id.equals(wanted, ignoreCase = true) }
        ?: skills.find { it.name.equals(wanted, ignoreCase = true) }
        ?: skills.find { it.id == SkillParser.idFor(wanted) }
}

private fun unknown(skills: List<Skill>, name: String) =
    "Error: there is no skill named \"$name\". Available skills: ${skills.joinToString(", ") { it.id }}"

private fun bounded(text: String, maxChars: Int) =
    if (text.length <= maxChars) text else text.take(maxChars) + "\n[cut: the text is longer than $maxChars characters]"

class UseSkillTool(private val skills: () -> List<Skill>, private val maxChars: Int = 6000) : Tool {
    override val spec = ToolSpec(
        "use_skill",
        "Load the full instructions of one of your skills. Call it before doing a task a skill covers, then follow what it returns.",
        """{"type":"object","properties":{"name":{"type":"string","description":"Skill name from the list of skills"}},"required":["name"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val name = args.optString("name")
        val available = skills()
        val skill = find(available, name) ?: return unknown(available, name)
        log.add(LogKind.NOTE, "Skill: ${skill.name}", skill.instructions)
        val notes = buildList {
            if (skill.tools.isNotEmpty()) {
                add("These instructions expect the tools ${skill.tools.joinToString(", ")}. If one is not available to you, do the task without it.")
            }
            if (skill.files.isNotEmpty()) {
                add("Extra files, read them with read_skill_file when the instructions point to them: ${skill.files.joinToString(", ")}")
            }
        }
        return (listOf("# ${skill.name}", bounded(skill.instructions, maxChars)) + notes).joinToString("\n\n")
    }
}

class ReadSkillFileTool(
    private val store: SkillStore,
    private val skills: () -> List<Skill>,
    private val maxChars: Int = 6000,
) : Tool {
    override val spec = ToolSpec(
        "read_skill_file",
        "Read an extra file that belongs to a skill. Use it only for files the skill's instructions mention.",
        """{"type":"object","properties":{"name":{"type":"string","description":"Skill name"},""" +
            """"file":{"type":"string","description":"File path as listed by use_skill"}},"required":["name","file"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val name = args.optString("name")
        val file = args.optString("file")
        val available = skills()
        val skill = find(available, name) ?: return unknown(available, name)
        val text = store.readFile(skill.id, file)
            ?: return "Error: the skill ${skill.id} has no file \"$file\". Its files: ${skill.files.joinToString(", ").ifEmpty { "none" }}"
        log.add(LogKind.NOTE, "Skill file: ${skill.id}/$file", text)
        return bounded(text, maxChars)
    }
}
