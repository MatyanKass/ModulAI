package com.modulai.app.ui.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.app.skills.Extensions
import com.modulai.app.skills.McpState
import com.modulai.app.skills.McpStatus
import com.modulai.core.skills.mcp.McpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Marks the dialog as creating a server rather than editing one. */
private const val NEW_SERVER = ""

@Composable
internal fun McpTab(extensions: Extensions, scope: CoroutineScope) {
    val servers by extensions.servers.collectAsState()
    val status by extensions.status.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    // Shows the state of the enabled servers without waiting for the next chat message.
    LaunchedEffect(servers) { extensions.connectServers() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card { Text(stringResource(R.string.mcp_about), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        item { Button(onClick = { editing = NEW_SERVER }) { Text(stringResource(R.string.mcp_add)) } }
        if (servers.isEmpty()) item {
            Text(stringResource(R.string.mcp_empty), modifier = Modifier.padding(top = 16.dp))
        }
        items(servers, key = { it.id }) { server ->
            ListItem(
                modifier = Modifier.clickable { editing = server.id },
                headlineContent = { Text(server.name) },
                supportingContent = {
                    Column {
                        Text(server.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusLine(server.enabled, status[server.id])
                    }
                },
                trailingContent = {
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { on -> scope.launch { extensions.saveServer(server.copy(enabled = on)) } },
                    )
                },
            )
        }
    }

    editing?.let { id ->
        val server = servers.find { it.id == id }
        if (server != null || id == NEW_SERVER) ServerDialog(
            server = server,
            extensions = extensions,
            onDismiss = { editing = null },
            onSave = { config ->
                editing = null
                scope.launch { extensions.saveServer(config) }
            },
            onDelete = {
                editing = null
                deleteId = id
            },
        )
    }
    servers.find { it.id == deleteId }?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.mcp_delete_title)) },
            text = { Text(server.name) },
            confirmButton = {
                TextButton(onClick = {
                    deleteId = null
                    scope.launch { extensions.deleteServer(server.id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatusLine(enabled: Boolean, status: McpStatus?) {
    val failed = enabled && status?.state == McpState.FAILED
    val text = when {
        !enabled -> stringResource(R.string.mcp_status_off)
        status == null -> stringResource(R.string.mcp_status_idle)
        status.state == McpState.CONNECTING -> stringResource(R.string.mcp_status_connecting)
        status.state == McpState.FAILED -> stringResource(R.string.mcp_status_failed, status.error.orEmpty())
        else -> pluralStringResource(R.plurals.mcp_status_connected, status.tools.size, status.tools.size)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ServerDialog(
    server: McpServerConfig?,
    extensions: Extensions,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(server?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(server?.url.orEmpty()) }
    var headerName by rememberSaveable { mutableStateOf(server?.headerName.orEmpty()) }
    // Deliberately not saveable: the secret must not end up in the saved instance state.
    var headerValue by remember { mutableStateOf(server?.headerValue.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<McpStatus?>(null) }

    val address = url.trim()
    val valid = address.startsWith("https://") || address.startsWith("http://")
    val draft = McpServerConfig(
        id = server?.id ?: NEW_SERVER,
        name = name.trim().ifEmpty { address.substringAfter("://").substringBefore('/') },
        url = address,
        headerName = headerName.trim(),
        headerValue = headerValue.trim(),
        enabled = server?.enabled ?: true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.mcp_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.mcp_url)) },
                    supportingText = { Text(stringResource(R.string.mcp_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headerValue,
                    onValueChange = { headerValue = it },
                    label = { Text(stringResource(R.string.mcp_header_value)) },
                    supportingText = { Text(stringResource(R.string.mcp_header_value_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headerName,
                    onValueChange = { headerName = it },
                    label = { Text(stringResource(R.string.mcp_header_name)) },
                    supportingText = { Text(stringResource(R.string.mcp_header_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = valid && !testing,
                    onClick = {
                        testing = true
                        result = null
                        scope.launch {
                            try {
                                result = extensions.test(draft)
                            } finally {
                                testing = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.mcp_test)) }
                if (testing) {
                    Hint(stringResource(R.string.mcp_testing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                result?.let { TestResult(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(draft) }) { Text(stringResource(R.string.ext_save)) }
        },
        dismissButton = {
            Row {
                if (server != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun TestResult(status: McpStatus) {
    val failed = status.state == McpState.FAILED
    val text = when {
        failed -> stringResource(R.string.mcp_status_failed, status.error.orEmpty())
        status.tools.isEmpty() -> stringResource(R.string.mcp_test_ok_empty, status.protocol.orEmpty())
        else -> stringResource(R.string.mcp_test_ok, status.protocol.orEmpty(), status.tools.joinToString(", "))
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
