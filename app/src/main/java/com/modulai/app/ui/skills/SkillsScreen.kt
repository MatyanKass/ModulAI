package com.modulai.app.ui.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.app.skills.Extensions

private enum class Section(val label: Int) {
    SKILLS(R.string.ext_tab_skills),
    MCP(R.string.ext_tab_mcp),
}

/** Where the user manages what extends the chat model: skills and MCP servers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val extensions = remember { Extensions.of(context) }
    var section by rememberSaveable { mutableStateOf(Section.SKILLS) }
    LaunchedEffect(Unit) { extensions.load() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ext_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = section.ordinal) {
                Section.entries.forEach {
                    Tab(selected = section == it, onClick = { section = it }, text = { Text(stringResource(it.label)) })
                }
            }
            // Installs and saves run in the app scope: leaving the screen must not cut them short.
            when (section) {
                Section.SKILLS -> SkillsTab(extensions, container.scope)
                Section.MCP -> McpTab(extensions, container.scope)
            }
        }
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
