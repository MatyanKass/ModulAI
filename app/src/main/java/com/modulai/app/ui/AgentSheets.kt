package com.modulai.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogEntry
import com.modulai.core.agent.LogKind

/** The "closed workspace": everything the model did that is not part of the conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(log: AgentLog, onDismiss: () -> Unit) {
    val entries by log.entries.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.workspace_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.workspace_empty),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(entries.reversed()) { LogRow(it) }
        }
    }
}

@Composable
fun LogRow(entry: LogEntry) {
    var expanded by remember(entry) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(enabled = entry.detail.isNotEmpty()) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            stringResource(
                when (entry.kind) {
                    LogKind.THOUGHT -> R.string.log_thought
                    LogKind.TOOL_CALL -> R.string.log_tool_call
                    LogKind.TOOL_RESULT -> R.string.log_tool_result
                    LogKind.SEARCH -> R.string.log_search
                    LogKind.PAGE -> R.string.log_page
                    LogKind.NOTE -> R.string.log_note
                    LogKind.ERROR -> R.string.log_error
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.kind == LogKind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        )
        Text(entry.title, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        if (expanded) {
            SelectionContainer {
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListSheet(container: AppContainer, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val chats = remember(revision) { container.chats.list() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.chats_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (chats.isEmpty()) {
            Text(
                stringResource(R.string.chats_empty),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(chats, key = { it.id }) { chat ->
                ListItem(
                    modifier = Modifier.clickable {
                        container.chat.open(chat.id)
                        onDismiss()
                    },
                    headlineContent = {
                        Text(chat.title.ifEmpty { stringResource(R.string.chat_new) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                chat.characterName,
                                DateUtils.getRelativeTimeSpanString(chat.updatedAt).toString(),
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            container.chat.delete(chat.id)
                            revision++
                        }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                    },
                )
            }
        }
    }
}
