package com.modulai.app.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.modulai.app.DownloadItem
import com.modulai.app.DownloadState
import com.modulai.app.ModelPrefs
import com.modulai.app.R
import com.modulai.core.models.Catalog
import com.modulai.core.models.CatalogEntry
import com.modulai.core.models.HfLink
import com.modulai.core.models.ModelCategory
import com.modulai.core.models.ModelStore
import com.modulai.core.models.RamFit
import com.modulai.core.models.ramFit

private val ModelCategory.label: Int
    @StringRes get() = when (this) {
        ModelCategory.CHAT -> R.string.mod_chat
        ModelCategory.RP -> R.string.mod_rp
        ModelCategory.RESEARCH -> R.string.mod_research
        ModelCategory.CODE -> R.string.mod_code
        ModelCategory.OTHER -> R.string.catalog_other
    }

private val FILTERS = listOf(null, ModelCategory.CHAT, ModelCategory.RP, ModelCategory.RESEARCH, ModelCategory.CODE)

/** The curated catalog. [catalog] is null while it is being read; [header] puts the download queue on top. */
@Composable
internal fun CatalogSection(
    catalog: Catalog?,
    installed: Set<String>,
    queue: List<DownloadItem>,
    totalRam: Long,
    prefs: ModelPrefs,
    onDownload: (HfLink.File) -> Unit,
    header: LazyListScope.() -> Unit,
) {
    var category by rememberSaveable { mutableStateOf<ModelCategory?>(null) }
    var showUncensored by remember { mutableStateOf(prefs.showUncensored) }
    val language = LocalConfiguration.current.locales[0].language
    val queued = queue.filter { it.state != DownloadState.FAILED }.mapTo(HashSet()) { it.fileName }
    val groups = remember(catalog, category, showUncensored) {
        catalog?.models.orEmpty()
            .filter { (category == null || it.category == category) && (showUncensored || !it.uncensored) }
            .groupBy { it.category }
            .toSortedMap()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        header()
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.forEach { filter ->
                    FilterChip(
                        selected = category == filter,
                        onClick = { category = filter },
                        label = { Text(stringResource(filter?.label ?: R.string.catalog_all)) },
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = showUncensored,
                    role = Role.Switch,
                    onValueChange = {
                        showUncensored = it
                        prefs.showUncensored = it
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.catalog_show_uncensored), Modifier.weight(1f))
                Switch(checked = showUncensored, onCheckedChange = null)
            }
        }
        when {
            catalog == null -> Unit
            catalog.models.isEmpty() -> item { Text(stringResource(R.string.catalog_empty)) }
            groups.isEmpty() -> item { Text(stringResource(R.string.catalog_none_match)) }
            else -> groups.forEach { (group, entries) ->
                if (category == null) item(key = "group-${group.name}") {
                    Text(
                        stringResource(group.label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(entries, key = { "entry-${it.id}" }) { entry ->
                    val fileName = ModelStore.sanitize(entry.link.fileName)
                    CatalogCard(
                        entry = entry,
                        description = entry.describe(language),
                        fit = if (entry.sizeBytes > 0 || entry.minRamGb > 0) {
                            ramFit(entry.sizeBytes, totalRam, entry.minRamGb)
                        } else null,
                        installed = fileName in installed,
                        queued = fileName in queued,
                        onDownload = { onDownload(entry.link) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    entry: CatalogEntry,
    description: String,
    fit: RamFit?,
    installed: Boolean,
    queued: Boolean,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            val facts = listOf(
                entry.params,
                entry.quant,
                if (entry.sizeBytes > 0) Formatter.formatShortFileSize(context, entry.sizeBytes) else "",
                entry.languages.joinToString(", ") { it.uppercase() },
            ).filter { it.isNotEmpty() }
            if (facts.isNotEmpty()) Text(
                facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.thinking || entry.tools || entry.uncensored) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val neutral = MaterialTheme.colorScheme.secondaryContainer
                    if (entry.thinking) Badge(R.string.badge_thinking, neutral)
                    if (entry.tools) Badge(R.string.badge_tools, neutral)
                    if (entry.uncensored) Badge(R.string.badge_uncensored, MaterialTheme.colorScheme.errorContainer)
                }
            }
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fit?.let { stringResource(it.label) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fit == RamFit.TOO_LARGE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when {
                    installed -> OutlinedButton(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.catalog_installed))
                    }
                    queued -> OutlinedButton(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.catalog_queued))
                    }
                    else -> Button(onClick = onDownload) { Text(stringResource(R.string.catalog_download)) }
                }
            }
        }
    }
}

@Composable
private fun Badge(@StringRes label: Int, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
