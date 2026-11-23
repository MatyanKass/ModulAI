package com.modulai.core.code

import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.Tool
import com.modulai.core.runtime.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The coder's tools over one project. Written for small local models: few tools, several accepted
 * spellings per argument, and results that say what happened and what to do next.
 *
 * Every change is recorded in [changes] first. [maxReadChars] bounds what one call returns, so a
 * single file cannot fill the context. [onChange] runs after each modification.
 */
fun codeTools(
    project: ProjectDir,
    changes: ChangeLog,
    maxReadChars: Int = 6000,
    onChange: () -> Unit = {},
): List<Tool> = listOf(
    FileTool(
        "list_files",
        "List all files of the project with their sizes.",
        """{"type":"object","properties":{}}""",
    ) { listFiles(project) },
    FileTool(
        "read_file",
        "Read a text file. Long files are cut: pass start_line and end_line to read another part.",
        """{"type":"object","properties":{"path":{"type":"string","description":"File path inside the project, like src/app.js"},"start_line":{"type":"integer","description":"First line to read, from 1"},"end_line":{"type":"integer","description":"Last line to read"}},"required":["path"]}""",
    ) { args -> readFile(project, args, maxReadChars) },
    FileTool(
        "write_file",
        "Create a file or replace it entirely. content must be the complete file, not a fragment.",
        """{"type":"object","properties":{"path":{"type":"string","description":"File path inside the project, like src/app.js"},"content":{"type":"string","description":"The whole new text of the file"}},"required":["path","content"]}""",
    ) { args -> writeFile(project, changes, args).also { onChange() } },
    FileTool(
        "edit_file",
        "Change part of a file: old_text is replaced with new_text. old_text must be copied exactly " +
            "from the file and must occur in it only once.",
        """{"type":"object","properties":{"path":{"type":"string","description":"File path inside the project"},"old_text":{"type":"string","description":"Exact fragment that is in the file now"},"new_text":{"type":"string","description":"What to put in its place"}},"required":["path","old_text","new_text"]}""",
    ) { args -> editFile(project, changes, args).also { onChange() } },
    FileTool(
        "delete_file",
        "Delete a file.",
        """{"type":"object","properties":{"path":{"type":"string","description":"File path inside the project"}},"required":["path"]}""",
    ) { args -> deleteFile(project, changes, args).also { onChange() } },
    FileTool(
        "search_files",
        "Find text in all files of the project. Returns matching lines as path:line: text.",
        """{"type":"object","properties":{"query":{"type":"string","description":"Text to find"},"regex":{"type":"boolean","description":"Treat query as a regular expression"}},"required":["query"]}""",
    ) { args -> searchFiles(project, args) },
)

private class FileTool(
    name: String,
    description: String,
    schema: String,
    private val body: (JSONObject) -> String,
) : Tool {
    override val spec = ToolSpec(name, description, schema)

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val result = withContext(Dispatchers.IO) { body(args) }
        log.add(LogKind.TOOL_RESULT, result.lineSequence().first().take(120), result)
        return result
    }
}

private class BadArguments(message: String) : Exception(message)

/** The first of [names] that is present; models often rename arguments. */
private fun JSONObject.text(vararg names: String): String? = names.firstOrNull { has(it) && !isNull(it) }?.let { optString(it) }

private fun JSONObject.path(): String =
    text("path", "file", "file_path", "filepath", "filename", "name")?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw BadArguments("Missing argument: path (the file path inside the project)")

private fun kb(bytes: Long) = if (bytes < 1024) "$bytes B" else "${(bytes + 512) / 1024} KB"

private const val MAX_LISTED = 200

private fun listFiles(project: ProjectDir): String {
    val files = project.files()
    if (files.isEmpty()) return "The project is empty. Create files with write_file."
    val shown = files.take(MAX_LISTED).joinToString("\n") { "${it.path} (${kb(it.size)})" }
    val more = if (files.size > MAX_LISTED) "\n… and ${files.size - MAX_LISTED} more files" else ""
    return "${files.size} files:\n$shown$more"
}

private fun readFile(project: ProjectDir, args: JSONObject, maxChars: Int): String {
    val path = project.normalize(args.path())
    val lines = splitLines(project.read(path))
    if (lines.isEmpty()) return "$path is empty."
    val first = args.optInt("start_line", 1).coerceIn(1, lines.size)
    val wanted = args.optInt("end_line", lines.size).coerceIn(first, lines.size)

    var last = first - 1
    var chars = 0
    while (last < wanted && (last < first || chars + lines[last].length + 1 <= maxChars)) {
        chars += lines[last].length + 1
        last++
    }
    val body = lines.subList(first - 1, last).joinToString("\n").take(maxChars)
    val whole = first == 1 && last == lines.size
    val header = if (whole) "$path (${lines.size} lines):" else "$path, lines $first-$last of ${lines.size}:"
    val note = if (last < wanted) "\n[Cut here. Call read_file with start_line=${last + 1} to read on.]" else ""
    return "$header\n$body$note"
}

/** Models often wrap the file in a Markdown code fence; the fence is not part of the file. */
private fun stripFence(path: String, content: String): String {
    if (path.endsWith(".md", ignoreCase = true)) return content
    val trimmed = content.trim()
    if (!trimmed.startsWith("```") || !trimmed.endsWith("```") || trimmed.indexOf('\n') < 0) return content
    val inner = trimmed.substring(trimmed.indexOf('\n') + 1, trimmed.length - 3)
    return if ("```" in inner) content else inner
}

private fun writeFile(project: ProjectDir, changes: ChangeLog, args: JSONObject): String {
    val path = project.normalize(args.path())
    val raw = args.text("content", "text", "code", "contents", "data")
        ?: throw BadArguments("Missing argument: content (the whole text of the file)")
    val content = stripFence(path, raw)
    val old = if (project.exists(path)) splitLines(project.read(path)).size else null
    changes.record(project, path)
    project.write(path, content)
    val lines = splitLines(content).size
    return if (old == null) "Created $path ($lines lines)." else "Replaced $path: $lines lines now, $old before."
}

private fun editFile(project: ProjectDir, changes: ChangeLog, args: JSONObject): String {
    val path = project.normalize(args.path())
    val old = args.text("old_text", "old", "old_string", "old_str", "search", "find")?.replace("\r\n", "\n")
    val new = args.text("new_text", "new", "new_string", "new_str", "replace", "replacement")?.replace("\r\n", "\n")
    if (old.isNullOrEmpty()) throw BadArguments("Missing argument: old_text (the exact fragment to replace). To write a whole file use write_file")
    if (new == null) throw BadArguments("Missing argument: new_text (the replacement; an empty string removes the fragment)")
    val text = project.read(path).replace("\r\n", "\n")

    val at = text.indexOf(old)
    if (at < 0) throw BadArguments(notFound(path, text, old))
    if (text.indexOf(old, at + 1) >= 0) {
        val where = generateSequence(at) { text.indexOf(old, it + 1).takeIf { i -> i >= 0 } }
            .map { lineOf(text, it) }.toList()
        throw BadArguments(
            "old_text occurs ${where.size} times in $path (lines ${where.take(8).joinToString(", ")}). " +
                "Nothing was changed. Add the lines around the place to old_text so that it matches only once.",
        )
    }
    if (old == new) return "Nothing to change in $path: old_text and new_text are the same."
    changes.record(project, path)
    project.write(path, text.substring(0, at) + new + text.substring(at + old.length))
    return "Edited $path at line ${lineOf(text, at)}: ${splitLines(old).size} lines replaced with ${splitLines(new).size}."
}

private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

/** Explains a failed match: most often the model retyped the fragment with different whitespace. */
private fun notFound(path: String, text: String, old: String): String {
    fun squeeze(s: String) = s.filterNot { it.isWhitespace() }
    val wanted = splitLines(old).map(::squeeze).filter { it.isNotEmpty() }
    val lines = splitLines(text)
    val start = wanted.firstOrNull()?.let { first -> lines.indexOfFirst { first in squeeze(it) } } ?: -1
    val hint = when {
        start < 0 -> "The file has no such text. Call read_file and copy the fragment from its output."
        squeeze(old) in squeeze(text) ->
            "The same text is near line ${start + 1}, but its spaces, indentation or line breaks differ. " +
                "Copy the fragment exactly as read_file shows it."
        else -> "Only its beginning matches, near line ${start + 1}. Call read_file and copy the fragment from its output."
    }
    return "old_text was not found in $path. Nothing was changed. $hint"
}

private fun deleteFile(project: ProjectDir, changes: ChangeLog, args: JSONObject): String {
    val path = project.normalize(args.path())
    if (!project.exists(path)) throw WorkspaceException(Refusal.NOT_FOUND, "\"$path\" does not exist. Call list_files to see the files")
    changes.record(project, path)
    project.delete(path)
    return "Deleted $path."
}

private const val MAX_MATCHES = 50
private const val MAX_MATCH_LINE = 200

private fun searchFiles(project: ProjectDir, args: JSONObject): String {
    val query = args.text("query", "pattern", "text", "q")?.takeIf { it.isNotEmpty() }
        ?: throw BadArguments("Missing argument: query (the text to find)")
    // A pattern that does not compile is searched for as plain text instead of failing the call.
    val regex = if (args.optBoolean("regex", false)) runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull() else null
    val matches = ArrayList<String>()
    var total = 0
    for (file in project.files()) {
        val text = runCatching { project.read(file.path) }.getOrNull() ?: continue
        splitLines(text).forEachIndexed { i, line ->
            if (regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)) {
                total++
                if (matches.size < MAX_MATCHES) matches += "${file.path}:${i + 1}: ${line.trim().take(MAX_MATCH_LINE)}"
            }
        }
    }
    if (total == 0) return "No matches for \"$query\"."
    val more = if (total > MAX_MATCHES) "\n… and ${total - MAX_MATCHES} more matches. Make the query more specific." else ""
    return "$total matches for \"$query\":\n${matches.joinToString("\n")}$more"
}
