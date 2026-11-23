package com.modulai.core.skills

import java.io.IOException

/** An installed skill: instructions the chat model can pull in on demand. */
data class Skill(
    /** Folder name inside the store; also what the model passes to `use_skill`. */
    val id: String,
    val name: String,
    val description: String,
    /** Tool names the instructions rely on. Informational: the skill still works without them. */
    val tools: List<String>,
    val language: String?,
    val instructions: String,
    /** Extra text files next to SKILL.md, as paths relative to the skill folder. */
    val files: List<String>,
    val enabled: Boolean,
    val builtIn: Boolean,
)

/** The content of a SKILL.md file. */
data class SkillManifest(
    val name: String,
    val description: String,
    val tools: List<String>,
    val language: String?,
    val instructions: String,
)

class SkillFormatException(message: String) : IOException(message)

/**
 * Reads SKILL.md: an optional front matter block between `---` lines, then Markdown.
 * Only the YAML subset skills use in practice is understood: scalars (plain, quoted, `|` and `>`
 * blocks) and flat lists (inline or dashed). Unknown keys are ignored.
 */
object SkillParser {
    const val MAX_CHARS = 48_000
    const val FILE_NAME = "SKILL.md"
    private const val MAX_DESCRIPTION = 300
    private val BLOCK_SCALAR = Regex("[|>][+-]?")

    fun parse(text: String, fallbackName: String): SkillManifest {
        if (text.length > MAX_CHARS) throw SkillFormatException("SKILL.md is larger than $MAX_CHARS characters")
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val first = lines.indexOfFirst { it.isNotBlank() }
        val close = if (first >= 0 && lines[first].trim() == "---") {
            (first + 1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
        } else null

        val fields = if (close != null) frontMatter(lines.subList(first + 1, close)) else emptyMap()
        val body = (if (close != null) lines.drop(close + 1) else lines).joinToString("\n").trim()
        if (body.isEmpty()) throw SkillFormatException("SKILL.md has no instructions")

        val description = fields["description"]?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: firstTextLine(body)
        return SkillManifest(
            name = fields["name"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = description.replace(Regex("""\s+"""), " ").trim().take(MAX_DESCRIPTION),
            tools = fields["tools"].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() },
            language = fields["language"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            instructions = body,
        )
    }

    /** A stable folder-safe identifier for a skill called [name]. */
    fun idFor(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).trim('-').ifEmpty { "skill" }

    private fun firstTextLine(body: String): String =
        body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()

    /** Every key maps to its values: one for a scalar, several for a list. */
    private fun frontMatter(lines: List<String>): Map<String, List<String>> {
        val fields = HashMap<String, List<String>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i++]
            val colon = line.indexOf(':')
            if (line.isBlank() || line.first().isWhitespace() || line.startsWith("#") || colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            // Lines that belong to this key: indented ones, and dash items even when not indented.
            val nested = ArrayList<String>()
            while (i < lines.size && (lines[i].isBlank() || lines[i].first().isWhitespace() || lines[i].startsWith("- "))) {
                nested += lines[i++]
            }
            val continuation = nested.map { it.trim() }.filter { it.isNotEmpty() }

            fields[key] = when {
                BLOCK_SCALAR.matches(value) -> listOf(continuation.joinToString(if (value.startsWith("|")) "\n" else " "))
                value.startsWith("[") -> inlineList(value)
                value.isEmpty() && continuation.all { it.startsWith("-") } ->
                    continuation.map { scalar(it.removePrefix("-").trim()) }.filter { it.isNotEmpty() }
                else -> listOf((listOf(scalar(value)) + continuation).joinToString(" ").trim())
            }
        }
        return fields
    }

    private fun inlineList(value: String): List<String> {
        val inner = value.substringAfter('[').substringBeforeLast(']')
        val items = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in inner) {
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    current.append(c)
                }
                c == ',' -> {
                    items += scalar(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        items += scalar(current.toString().trim())
        return items.filter { it.isNotEmpty() }
    }

    private fun scalar(raw: String): String = when {
        raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ->
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'') -> raw.substring(1, raw.length - 1).replace("''", "'")
        // In plain scalars a comment starts at " #".
        else -> raw.substringBefore(" #").trim()
    }
}
