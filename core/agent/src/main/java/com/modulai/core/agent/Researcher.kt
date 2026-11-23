package com.modulai.core.agent

import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Effort
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.TextEvent
import com.modulai.core.runtime.TextSession
import com.modulai.core.runtime.ToolSpec
import com.modulai.core.runtime.parseReply
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

enum class ResearchDepth(val queries: Int, val pages: Int) {
    QUICK(2, 5),
    NORMAL(3, 10),
    DEEP(5, 24),
}

data class Source(val index: Int, val title: String, val url: String, val lastModified: String?)

data class ResearchReport(val text: String, val sources: List<Source>)

sealed interface ResearchProgress {
    data class Searching(val query: String) : ResearchProgress
    data class Reading(val done: Int, val total: Int, val url: String) : ResearchProgress
    /** The report as written so far. */
    data class Writing(val text: String) : ResearchProgress
}

/**
 * Web research that fits a small context window: every page is condensed into notes on its
 * own, and only the notes are given to the model for the final report.
 */
class Researcher(private val session: TextSession, private val web: Web, private val log: AgentLog) {

    suspend fun run(
        topic: String,
        depth: ResearchDepth,
        params: SamplingParams,
        onProgress: (ResearchProgress) -> Unit = {},
    ): ResearchReport {
        // At roughly 3 characters per token this gives a page a third of the window,
        // leaving the rest for the instructions and the notes being written.
        val pageChars = session.contextTokens.coerceIn(1500, 12_000)
        val quick = params.copy(effort = Effort.LOW, temperature = 0.3f)

        val queries = planQueries(topic, depth.queries, quick)
        val found = LinkedHashMap<String, SearchResult>()
        for (query in queries) {
            onProgress(ResearchProgress.Searching(query))
            log.add(LogKind.SEARCH, query)
            try {
                web.search(query).forEach { found.putIfAbsent(it.url, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add(LogKind.ERROR, query, e.message.orEmpty())
            }
        }
        if (found.isEmpty()) throw IllegalStateException("Search returned nothing for: ${queries.joinToString("; ")}")

        val targets = found.values.take(depth.pages)
        val sources = ArrayList<Source>()
        val notes = ArrayList<String>()
        targets.forEachIndexed { i, result ->
            onProgress(ResearchProgress.Reading(i, targets.size, result.url))
            try {
                val page = web.fetch(result.url, pageChars)
                val note = ask(
                    "You extract facts from a web page for a research task.\n" +
                        "Task: $topic\n\n" +
                        "List the facts, figures, names and dates from the page that help with the task, as short " +
                        "bullet points. Keep the language of the task. If the page has nothing useful, answer " +
                        "exactly NONE.\n\nPage: ${page.title}\n${page.text}",
                    quick.copy(maxTokens = 350),
                ).trim()
                if (note.isEmpty() || note.uppercase().startsWith("NONE")) {
                    log.add(LogKind.PAGE, page.title.ifEmpty { result.url }, "${result.url}\n(nothing relevant)")
                } else {
                    val index = sources.size + 1
                    sources += Source(index, page.title.ifEmpty { result.title }, result.url, page.lastModified)
                    notes += "[$index] ${sources.last().title}" +
                        (page.lastModified?.let { " (updated $it)" }.orEmpty()) + "\n$note"
                    log.add(LogKind.PAGE, sources.last().title, "${result.url}\n\n$note")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add(LogKind.ERROR, result.url, e.message.orEmpty())
            }
        }
        onProgress(ResearchProgress.Reading(targets.size, targets.size, ""))
        if (notes.isEmpty()) throw IllegalStateException("None of the ${targets.size} pages could be read or was relevant")

        // Notes from the first sources win if everything does not fit.
        val budget = session.contextTokens * 3 / 2
        val joined = StringBuilder()
        for (note in notes) {
            if (joined.length + note.length > budget) break
            joined.append(note).append("\n\n")
        }
        val report = ask(
            "Write a research report for the task below using only the notes. Structure it with short " +
                "headings, state concrete facts, and cite sources as [1], [2] after the statements they " +
                "support. Point out where sources disagree or where information is missing. Write in the " +
                "language of the task.\n\nTask: $topic\n\nNotes:\n$joined",
            params,
        ) { onProgress(ResearchProgress.Writing(it)) }
        log.add(LogKind.NOTE, "Report: ${sources.size} sources", report)
        return ResearchReport(report, sources)
    }

    private suspend fun planQueries(topic: String, count: Int, params: SamplingParams): List<String> {
        val answer = ask(
            "Write $count different web search queries that together would find the information needed for " +
                "the task below. One query per line, no numbering, no quotes, no explanations. Use the language " +
                "most likely to give good results.\n\nTask: $topic",
            params.copy(maxTokens = 120),
        )
        val queries = answer.lines()
            .map { it.trim().trimStart('-', '*', '•').replace(Regex("""^\d+[.)]\s*"""), "").trim('"', ' ') }
            .filter { it.length in 3..200 }
            .distinct()
            .take(count)
        log.add(LogKind.NOTE, "Search plan", queries.joinToString("\n"))
        return queries.ifEmpty { listOf(topic.take(200)) }
    }

    private suspend fun ask(prompt: String, params: SamplingParams, onText: (String) -> Unit = {}): String {
        val text = StringBuilder()
        session.generate(listOf(ChatMessage(Role.USER, prompt)), params).collect { event ->
            if (event is TextEvent.Piece) {
                text.append(event.text)
                onText(answerOf(text.toString()))
            }
        }
        return answerOf(text.toString())
    }

    private fun answerOf(raw: String) = parseReply(raw).answer
}

/** Lets the chat model start a research run by itself. */
class ResearchTool(
    private val researcher: Researcher,
    private val params: () -> SamplingParams,
    private val depth: () -> ResearchDepth,
) : Tool {
    override val spec = ToolSpec(
        "research",
        "Run thorough web research on a topic: several searches, many pages read, and a report with sources. " +
            "Slow (minutes). Use it only when the user asks for research or a detailed, sourced overview.",
        """{"type":"object","properties":{"topic":{"type":"string","description":"What to find out, in full"}},"required":["topic"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val topic = args.optString("topic").trim()
        if (topic.isEmpty()) return "Error: empty topic"
        val report = researcher.run(topic, depth(), params())
        return report.text + "\n\nSources:\n" + report.sources.joinToString("\n") { "[${it.index}] ${it.title} — ${it.url}" }
    }
}
