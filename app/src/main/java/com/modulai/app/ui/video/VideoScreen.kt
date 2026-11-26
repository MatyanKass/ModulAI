package com.modulai.app.ui.video

import android.content.Intent
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.app.video.VideoController
import com.modulai.app.video.VideoProcessor
import com.modulai.app.video.VideoSize
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { VideoController.get(context) }
    val state by controller.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.pick(uri)
    }

    // Processing takes many minutes; the screen must not lock and suspend the work.
    val view = LocalView.current
    LaunchedEffect(state.running) { view.keepScreenOn = state.running }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.running) {
        while (state.running) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mod_video)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.video_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                enabled = !state.running,
            ) { Text(stringResource(R.string.video_pick)) }

            state.info?.let { info ->
                Text(
                    stringResource(R.string.video_info, info.width, info.height, info.fps, info.frames,
                        DateUtils.formatElapsedTime(info.durationMs / 1000)),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Option(R.string.video_upscale, R.string.video_upscale_hint, state.upscale, !state.running) {
                    controller.options(upscale = it)
                }
                Text(stringResource(R.string.video_size), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoSize.entries.forEach { size ->
                        val (w, h) = VideoProcessor.outputSize(info.width, info.height, size)
                        FilterChip(
                            selected = state.size == size,
                            enabled = !state.running,
                            onClick = { controller.options(size = size) },
                            label = {
                                Text(
                                    stringResource(
                                        if (size == VideoSize.SAME) R.string.video_size_same else R.string.video_size_double,
                                        w, h,
                                    ),
                                )
                            },
                        )
                    }
                }
                Option(R.string.video_fps, R.string.video_fps_hint, state.doubleFps, !state.running) {
                    controller.options(doubleFps = it)
                }

                if (state.running) {
                    OutlinedButton(onClick = { controller.stop() }) { Text(stringResource(R.string.chat_stop)) }
                } else {
                    Button(onClick = { controller.start() }, enabled = state.upscale || state.doubleFps || state.size != VideoSize.SAME) {
                        Text(stringResource(R.string.video_start))
                    }
                }
            }

            if (state.running) {
                val p = state.progress
                val elapsed = DateUtils.formatElapsedTime((now - state.startedAt) / 1000)
                if (p == null) {
                    Text(stringResource(R.string.video_preparing, elapsed))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.video_progress, p.frame, p.frames, elapsed))
                    LinearProgressIndicator(progress = { p.frame.toFloat() / p.frames.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.result?.let { uri ->
                Text(stringResource(R.string.video_done))
                Button(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }) { Text(stringResource(R.string.video_open)) }
            }
        }
    }
}

@Composable
private fun Option(title: Int, hint: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
