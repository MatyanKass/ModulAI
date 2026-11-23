package com.modulai.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.core.agent.ResearchDepth
import com.modulai.core.agent.ResearchProgress
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchScreen(container: AppContainer, onBack: () -> Unit, onPickModel: () -> Unit) {
    val state by container.research.state.collectAsState()
    val chat by container.chat.state.collectAsState()
    val settings by container.runSettings.collectAsState()
    var topic by rememberSaveable { mutableStateOf("") }
    var showLog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mod_research)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { showLog = true }) {
                        Icon(Icons.Outlined.Terminal, stringResource(R.string.workspace_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (chat.model == null) {
                Text(stringResource(R.string.chat_pick_model))
                Button(onClick = onPickModel) { Text(stringResource(R.string.tab_models)) }
                return@Column
            }
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text(stringResource(R.string.research_topic)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
                enabled = !state.running,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResearchDepth.entries.forEach { depth ->
                    FilterChip(
                        selected = settings.researchDepth == depth,
                        enabled = !state.running,
                        onClick = { container.scope.launch { container.save(settings.copy(researchDepth = depth)) } },
                        label = { Text(stringResource(depthLabel(depth), depth.pages)) },
                    )
                }
            }
            if (state.running) {
                OutlinedButton(onClick = { container.research.stop() }) { Text(stringResource(R.string.chat_stop)) }
            } else {
                Button(onClick = { container.research.start(topic) }, enabled = topic.isNotBlank()) {
                    Text(stringResource(R.string.research_start))
                }
            }

            when (val p = state.progress) {
                is ResearchProgress.Searching -> Status(stringResource(R.string.research_searching, p.query), null)
                is ResearchProgress.Reading ->
                    Status(stringResource(R.string.research_reading, p.done, p.total), p.done.toFloat() / p.total.coerceAtLeast(1))
                is ResearchProgress.Writing -> Status(stringResource(R.string.research_writing), null)
                null -> if (state.running) Status(stringResource(R.string.research_planning), null)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val text = state.report?.text ?: (state.progress as? ResearchProgress.Writing)?.text
            if (!text.isNullOrEmpty()) SelectionContainer { Text(text) }
            state.report?.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
                Text(stringResource(R.string.research_sources), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        sources.forEach { s ->
                            Text("[${s.index}] ${s.title}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(s.url, s.lastModified).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (showLog) WorkspaceSheet(container.research.log, onDismiss = { showLog = false })
}

private fun depthLabel(depth: ResearchDepth) = when (depth) {
    ResearchDepth.QUICK -> R.string.research_quick
    ResearchDepth.NORMAL -> R.string.research_normal
    ResearchDepth.DEEP -> R.string.research_deep
}

@Composable
private fun Status(text: String, fraction: Float?) {
    Column {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}
