package com.modulai.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.core.device.DeviceProfile
import com.modulai.core.device.NpuKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DeviceScreen(container: AppContainer) {
    // Free memory changes constantly, so re-read while the screen is visible.
    val profile by produceState<DeviceProfile?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { container.device.inspect() }
            delay(2000)
        }
    }
    val p = profile ?: return
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(R.string.dev_phone, "${p.manufacturer} ${p.model}")
        Row(R.string.dev_soc, "${p.socManufacturer} ${p.socModel}")
        Row(R.string.dev_cores, stringResource(R.string.dev_cores_value, p.cpuCores, p.bigCores))
        Row(
            R.string.dev_ram,
            stringResource(R.string.dev_ram_value, size(p.memory.availableBytes), size(p.memory.totalBytes)),
        )
        Row(
            R.string.dev_swap,
            if (p.memory.swapTotalBytes == 0L) stringResource(R.string.dev_swap_none)
            else stringResource(R.string.dev_ram_value, size(p.memory.swapFreeBytes), size(p.memory.swapTotalBytes)),
        )
        Row(
            R.string.dev_npu,
            stringResource(if (p.npu == NpuKind.QUALCOMM_HEXAGON) R.string.dev_npu_hexagon else R.string.dev_npu_none),
        )
        Row(R.string.dev_backends, p.backends.joinToString { it.name })
        Row(
            R.string.dev_engines,
            container.engines.all().joinToString { it.id }.ifEmpty { stringResource(R.string.dev_engines_none) },
        )
    }
}

@Composable
private fun Row(label: Int, value: String) {
    ListItem(headlineContent = { Text(value) }, overlineContent = { Text(stringResource(label)) })
}
