package com.modulai.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.ChatController
import com.modulai.app.R
import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.parseReply

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer, onBack: () -> Unit, onPickModel: () -> Unit) {
    val state by container.chat.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Follow the reply while it streams.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.model?.id ?: stringResource(R.string.chat_no_model),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.contextTokens > 0) {
                            Text(
                                stringResource(
                                    R.string.chat_stats, state.usedTokens, state.contextTokens, state.tokensPerSecond,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { container.chat.newChat() }) {
                        Icon(Icons.Outlined.AddComment, stringResource(R.string.chat_new))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Tune, stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Notice(stringResource(R.string.chat_loading))
                }
                if (state.trimmed > 0) Notice(stringResource(R.string.chat_trimmed, state.trimmed))
                if (state.truncated) Notice(stringResource(R.string.chat_truncated))
                state.error?.let { error ->
                    Notice(
                        if (error == ChatController.ERROR_TOO_LONG) stringResource(R.string.chat_too_long) else error,
                        isError = true,
                        onClick = { container.chat.dismissError() },
                    )
                }
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_hint)) },
                        enabled = state.model != null,
                        maxLines = 6,
                    )
                    if (state.generating || state.loading) {
                        FilledIconButton(onClick = { container.chat.stop() }, modifier = Modifier.padding(start = 8.dp)) {
                            Icon(Icons.Outlined.Stop, stringResource(R.string.chat_stop))
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                container.chat.send(input)
                                input = ""
                            },
                            enabled = state.model != null && input.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.chat_send))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.model == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.chat_pick_model), textAlign = TextAlign.Center)
                Button(onClick = onPickModel, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.tab_models))
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.messages) { index, message ->
                    Bubble(message, streaming = state.generating && index == state.messages.lastIndex)
                }
            }
        }
    }

    if (showSettings) RunSettingsSheet(container, onDismiss = { showSettings = false })
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
    )
}

@Composable
private fun Bubble(message: ChatMessage, streaming: Boolean) {
    val isUser = message.role == Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .padding(12.dp)
                .animateContentSize(),
        ) {
            if (isUser) {
                SelectionContainer { Text(message.content) }
            } else {
                val parsed = parseReply(message.content)
                if (parsed.thinking.isNotEmpty()) Thinking(parsed.thinking, open = parsed.thinkingOpen)
                if (parsed.answer.isNotEmpty()) {
                    SelectionContainer { Text(parsed.answer) }
                } else if (streaming && parsed.thinking.isEmpty()) {
                    Text("…")
                }
            }
        }
    }
}

/** The model's reasoning: collapsed once the answer starts, expandable on tap. */
@Composable
private fun Thinking(text: String, open: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }.padding(bottom = 8.dp)) {
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
