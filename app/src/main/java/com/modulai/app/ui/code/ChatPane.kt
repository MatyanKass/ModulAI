package com.modulai.app.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.app.code.CodeController
import com.modulai.app.code.CodeState
import com.modulai.core.agent.LogKind
import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.ParsedReply
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.parseReply

/** Tool-call markup the model writes; the call itself is shown in the workspace sheet. */
private val TOOL_CALL = Regex("""<tool_call>.*?(</tool_call>|$)""", RegexOption.DOT_MATCHES_ALL)

private class Shown(val message: ChatMessage, val reply: ParsedReply?)

/**
 * The conversation without what is only for the model: tool results, and assistant turns that
 * were nothing but a tool call. Those are in the workspace log.
 */
private fun visible(messages: List<ChatMessage>, running: Boolean): List<Shown> = messages.mapIndexedNotNull { index, message ->
    when (message.role) {
        Role.USER -> Shown(message, null)
        Role.ASSISTANT -> {
            val parsed = parseReply(message.content)
            val reply = parsed.copy(answer = TOOL_CALL.replace(parsed.answer, "").trim())
            val streaming = running && index == messages.lastIndex
            if (reply.answer.isEmpty() && reply.thinking.isEmpty() && !streaming) null else Shown(message, reply)
        }
        else -> null
    }
}

@Composable
internal fun ChatPane(state: CodeState, hasModel: Boolean, onPickModel: () -> Unit, onShowChanges: () -> Unit) {
    if (!hasModel) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.code_pick_model), textAlign = TextAlign.Center)
            Button(onClick = onPickModel, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.tab_models)) }
        }
        return
    }
    val shown = remember(state.messages, state.running) { visible(state.messages, state.running) }
    if (shown.isEmpty()) {
        Text(
            stringResource(R.string.code_chat_empty),
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val listState = rememberLazyListState()
    val showSummary = !state.running && state.changes.isNotEmpty()
    // Follow the reply while it streams.
    LaunchedEffect(shown.size, shown.last().message.content.length, showSummary) {
        listState.scrollToItem(if (showSummary) shown.size else shown.lastIndex, Int.MAX_VALUE)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(shown) { _, item -> Bubble(item) }
        if (showSummary) {
            item {
                AssistChip(
                    onClick = onShowChanges,
                    label = { Text(pluralStringResource(R.plurals.code_files_changed, state.changes.size, state.changes.size)) },
                )
            }
        }
    }
}

@Composable
private fun Bubble(item: Shown) {
    val reply = item.reply
    Box(Modifier.fillMaxWidth(), contentAlignment = if (reply == null) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (reply == null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .padding(12.dp),
        ) {
            when {
                reply == null -> SelectionContainer { Text(item.message.content) }
                else -> {
                    if (reply.thinking.isNotEmpty()) Thinking(reply.thinking, open = reply.thinkingOpen)
                    if (reply.answer.isNotEmpty()) SelectionContainer { Text(reply.answer) }
                    else if (reply.thinking.isEmpty()) Text("…")
                }
            }
        }
    }
}

/** The model's reasoning: collapsed once the answer starts, expandable on tap. */
@Composable
private fun Thinking(text: String, open: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }) {
        Text(
            stringResource(if (open) R.string.chat_thinking else R.string.chat_thought),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        if (expanded || open) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ChatInput(controller: CodeController, state: CodeState, modelReady: Boolean, loading: Boolean, onShowLog: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val log by controller.log.entries.collectAsState()
    Column {
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            // The latest step of the coder in one line; the sheet has the whole log.
            val step = log.lastOrNull { it.kind == LogKind.TOOL_CALL || it.kind == LogKind.TOOL_RESULT || it.kind == LogKind.ERROR }
            Notice(
                when {
                    step != null -> step.title
                    loading -> stringResource(R.string.chat_loading)
                    else -> stringResource(R.string.code_working)
                },
                isError = step?.kind == LogKind.ERROR,
                onClick = onShowLog,
            )
        }
        if (state.trimmed > 0) Notice(stringResource(R.string.chat_trimmed, state.trimmed))
        if (state.truncated) Notice(stringResource(R.string.chat_truncated))
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.code_chat_hint)) },
                enabled = modelReady,
                maxLines = 6,
            )
            if (state.running) {
                FilledIconButton(onClick = { controller.stop() }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Outlined.Stop, stringResource(R.string.chat_stop))
                }
            } else {
                FilledIconButton(
                    onClick = {
                        controller.send(input)
                        input = ""
                    },
                    enabled = modelReady && input.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
