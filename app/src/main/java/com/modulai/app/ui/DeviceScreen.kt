package com.modulai.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.modulai.core.runtime.Backend
import kotlinx.coroutines.launch
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
    var gpu by remember { mutableStateOf(GpuInfo(emptyList(), null)) }
    var npu by remember { mutableStateOf(GpuInfo(emptyList(), null)) }
    val backends by produceState(emptySet<Backend>()) {
        // What the text engine can actually use; the first read probes the GPU driver.
        value = withContext(Dispatchers.Default) { container.llama.backends }
        gpu = GpuInfo(container.llama.gpuDevices, container.llama.gpuUnavailableReason)
        npu = GpuInfo(container.llama.npuDevices, container.llama.npuUnavailableReason)
    }
    val scope = rememberCoroutineScope()
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
        Row(R.string.dev_backends, backends.joinToString { it.name })
        Row(
            R.string.dev_gpu,
            gpu.devices.joinToString().ifEmpty { gpu.reason ?: stringResource(R.string.dev_gpu_checking) },
        )
        Row(
            R.string.dev_npu_backend,
            npu.devices.joinToString().ifEmpty { npu.reason ?: stringResource(R.string.dev_gpu_checking) },
        )
        if (npu.devices.isEmpty() && npu.reason != null) {
            TextButton(
                onClick = {
                    scope.launch {
                        container.llama.retryNpu()
                        npu = GpuInfo(container.llama.npuDevices, container.llama.npuUnavailableReason)
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.dev_npu_retry)) }
        }
        if (gpu.devices.isEmpty() && gpu.reason != null) {
            TextButton(
                onClick = {
                    scope.launch {
                        container.llama.retryGpu()
                        gpu = GpuInfo(container.llama.gpuDevices, container.llama.gpuUnavailableReason)
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.dev_gpu_retry)) }
        }
        Row(
            R.string.dev_engines,
            container.engines.all().joinToString { it.id }.ifEmpty { stringResource(R.string.dev_engines_none) },
        )
    }
}

private data class GpuInfo(val devices: List<String>, val reason: String?)

@Composable
private fun Row(label: Int, value: String) {
    ListItem(headlineContent = { Text(value) }, overlineContent = { Text(stringResource(label)) })
}
