package com.modulai.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.BackendSetting
import com.modulai.app.Preset
import com.modulai.app.R
import com.modulai.app.RunSettings
import com.modulai.core.runtime.Effort
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSettingsSheet(container: AppContainer, onDismiss: () -> Unit) {
    val settings by container.runSettings.collectAsState()
    val scope = rememberCoroutineScope()
    fun save(s: RunSettings) {
        scope.launch { container.save(s) }
    }
    val cores = Runtime.getRuntime().availableProcessors()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Label(R.string.set_preset)
            Chips(Preset.entries, settings.preset, { presetLabel(it) }) { save(settings.withPreset(it)) }

            Label(R.string.set_context)
            Chips(RunSettings.CONTEXT_CHOICES, settings.contextTokens, { "${it / 1024}K" }) {
                save(settings.custom(contextTokens = it))
            }

            Label(R.string.set_effort)
            Chips(Effort.entries, settings.effort, { effortLabel(it) }) { save(settings.custom(effort = it)) }
            Hint(R.string.set_effort_hint)

            Label(R.string.set_backend)
            Chips(BackendSetting.entries, settings.backend, { backendLabel(it) }) { save(settings.copy(backend = it)) }
            Hint(R.string.set_backend_hint)
            if (settings.backend == BackendSetting.NPU) Hint(container.llama.npuNote)

            Label(R.string.set_temperature, "%.2f".format(settings.temperature))
            Slider(
                value = settings.temperature,
                onValueChange = { save(settings.copy(temperature = (it * 20).roundToInt() / 20f)) },
                valueRange = 0f..2f,
            )

            Label(R.string.set_threads, settings.threads.toString())
            Slider(
                value = settings.threads.toFloat(),
                onValueChange = { save(settings.copy(threads = it.roundToInt())) },
                valueRange = 1f..cores.toFloat(),
                steps = (cores - 2).coerceAtLeast(0),
            )

            Toggle(R.string.set_auto, R.string.set_auto_hint, settings.autoMode) { save(settings.copy(autoMode = it)) }
            Toggle(R.string.set_web_tools, R.string.set_web_tools_hint, settings.webTools) { save(settings.copy(webTools = it)) }
            Toggle(R.string.set_rp_emoji, R.string.set_rp_emoji_hint, settings.rpEmoji) { save(settings.copy(rpEmoji = it)) }
            Toggle(R.string.set_mlock, R.string.set_mlock_hint, settings.lockMemory) { save(settings.copy(lockMemory = it)) }
            Toggle(R.string.set_mmap, R.string.set_mmap_hint, settings.useMmap) { save(settings.copy(useMmap = it)) }

            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { save(settings.copy(systemPrompt = it)) },
                label = { Text(stringResource(R.string.set_system_prompt)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                minLines = 2,
                maxLines = 6,
            )
            Hint(R.string.set_reload_hint)
        }
    }
}

@Composable
private fun presetLabel(p: Preset) = stringResource(
    when (p) {
        Preset.LOW -> R.string.preset_low
        Preset.MEDIUM -> R.string.preset_medium
        Preset.HIGH -> R.string.preset_high
        Preset.CUSTOM -> R.string.preset_custom
    },
)

@Composable
private fun backendLabel(b: BackendSetting) = stringResource(
    when (b) {
        BackendSetting.CPU -> R.string.backend_cpu
        BackendSetting.AUTO -> R.string.backend_auto
        BackendSetting.GPU -> R.string.backend_gpu
        BackendSetting.NPU -> R.string.backend_npu
    },
)

@Composable
private fun effortLabel(e: Effort) = when (e) {
    Effort.LOW -> "Low"
    Effort.MEDIUM -> "Medium"
    Effort.HIGH -> "High"
    Effort.ULTRA -> "Ultra"
}

@Composable
private fun Label(title: Int, value: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: Int) = Hint(stringResource(text))

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun Toggle(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
