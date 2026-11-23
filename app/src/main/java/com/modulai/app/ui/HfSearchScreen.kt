package com.modulai.app.ui

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.core.models.HfApi
import com.modulai.core.models.HfRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/** Search query and results, held by the Models tab so they survive switching its sections. */
internal class HfSearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<HfRepo>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var job: Job? = null

    fun run(scope: CoroutineScope, token: String?) {
        if (query.isBlank()) return
        job?.cancel()
        job = scope.launch {
            busy = true
            error = null
            try {
                results = HfApi.searchGguf(query, token)
            } catch (e: IOException) {
                error = e.message ?: e.toString()
            }
            busy = false
        }
    }
}

@Composable
internal fun HfSearchSection(
    state: HfSearchState,
    onSearch: () -> Unit,
    onRepo: (String) -> Unit,
    header: LazyListScope.() -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT) }
    val submit = {
        keyboard?.hide()
        onSearch()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        header()
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                label = { Text(stringResource(R.string.search_hint)) },
                supportingText = { Text(stringResource(R.string.search_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = {
                    IconButton(onClick = submit, enabled = state.query.isNotBlank()) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.models_search))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val results = state.results
        if (results != null && results.isEmpty() && !state.busy && state.error == null) item {
            Text(stringResource(R.string.search_empty))
        }
        items(results.orEmpty(), key = { it.id }) { repo ->
            ListItem(
                modifier = Modifier.clickable { onRepo(repo.id) },
                headlineContent = { Text(repo.id) },
                supportingContent = {
                    Text(stringResource(R.string.search_stats, numbers.format(repo.downloads), numbers.format(repo.likes)))
                },
            )
        }
    }
}
