package com.modulai.app.ui.image

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.app.image.ImageController
import com.modulai.app.image.ImageIo
import com.modulai.app.image.ImageMode
import com.modulai.app.image.ImagePhase
import com.modulai.app.image.ImagePreset
import com.modulai.app.image.ImageProgress
import com.modulai.app.image.ImageRecord
import com.modulai.app.image.ImageSettings
import com.modulai.app.image.ModelProfile
import com.modulai.core.runtime.ImageSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The image module: text-to-image, image-to-image and upscaling on the device.
 * [container] is part of the screen contract; image state lives in [ImageController],
 * which outlives the activity.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ImageController.get(context) }
    val state by controller.state.collectAsState()
    val settings = state.settings
    SyncImageModels(controller)

    var mode by rememberSaveable { mutableStateOf(ImageMode.TXT2IMG) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ImageRecord?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) source = uri
    }
    val pick = { pickSource.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // Generation runs for minutes; a sleeping phone throttles the CPU to a crawl.
    val view = LocalView.current
    DisposableEffect(state.busy) {
        view.keepScreenOn = state.busy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.mod_image), style = MaterialTheme.typography.titleMedium)
                        val model = if (mode == ImageMode.UPSCALE) settings.upscaler else settings.checkpoint
                        Text(
                            model ?: stringResource(R.string.img_no_model),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Outlined.History, stringResource(R.string.img_history))
                    }
                    IconButton(onClick = { showModels = true }) {
                        Icon(Icons.Outlined.Inventory2, stringResource(R.string.img_models))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ImageMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { mode = option },
                        shape = SegmentedButtonDefaults.itemShape(index, ImageMode.entries.size),
                    ) { Text(stringResource(option.label), maxLines = 1) }
                }
            }

            val missingModel = if (mode == ImageMode.UPSCALE) settings.upscaler == null else settings.checkpoint == null
            if (missingModel) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                if (mode == ImageMode.UPSCALE) R.string.img_need_upscaler else R.string.img_need_model,
                            ),
                        )
                        Button(onClick = { showModels = true }) { Text(stringResource(R.string.img_models)) }
                    }
                }
            }

            if (mode != ImageMode.UPSCALE) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.img_prompt)) },
                    supportingText = { Text(stringResource(R.string.img_prompt_help)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.negativePrompt,
                    onValueChange = { controller.update(settings.copy(negativePrompt = it)) },
                    label = { Text(stringResource(R.string.img_negative)) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (mode != ImageMode.TXT2IMG) {
                SourcePicker(source, onPick = pick)
                if (mode == ImageMode.UPSCALE) {
                    Hint(stringResource(R.string.img_upscale_hint, (ImageController.MAX_UPSCALED_PIXELS / 1_000_000).toInt()))
                } else {
                    LabeledSlider(
                        stringResource(R.string.img_strength), "%.2f".format(settings.strength),
                        settings.strength, 0.05f..1f,
                    ) { controller.update(settings.copy(strength = (it * 20).roundToInt() / 20f)) }
                    Hint(stringResource(R.string.img_strength_hint))
                    if (settings.preset != ImagePreset.CUSTOM && settings.effectiveProfile() == ModelProfile.ONE_STEP) {
                        Hint(stringResource(R.string.img_one_step_edit))
                    }
                }
            }

            if (mode != ImageMode.UPSCALE) SamplingSettings(settings, controller::update)

            val progress = state.progress
            if (progress != null) {
                ProgressCard(progress, onStop = controller::stop)
            } else {
                Button(
                    enabled = !missingModel && (mode == ImageMode.TXT2IMG || source != null) &&
                        (mode == ImageMode.UPSCALE || prompt.isNotBlank()),
                    onClick = {
                        when (mode) {
                            ImageMode.TXT2IMG -> controller.generate(prompt, null)
                            ImageMode.IMG2IMG -> controller.generate(prompt, source)
                            ImageMode.UPSCALE -> source?.let(controller::upscale)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (mode == ImageMode.UPSCALE) R.string.img_upscale else R.string.img_generate)) }
            }

            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { controller.dismissError() }
                        .padding(12.dp),
                )
            }

            state.result?.let { record ->
                ResultView(
                    record,
                    onSave = {
                        scope.launch {
                            notice = try {
                                withContext(Dispatchers.IO) { ImageIo.saveToGallery(context, record.file) }
                                context.getString(R.string.img_saved)
                            } catch (e: Exception) {
                                context.getString(R.string.img_err_save, e.message ?: e.toString())
                            }
                        }
                    },
                    onShare = {
                        scope.launch {
                            try {
                                // Shared through MediaStore: other apps cannot read files under filesDir.
                                val uri = withContext(Dispatchers.IO) { ImageIo.saveToGallery(context, record.file) }
                                share(context, uri)
                            } catch (e: Exception) {
                                notice = context.getString(R.string.img_err_save, e.message ?: e.toString())
                            }
                        }
                    },
                    onUpscale = {
                        source = Uri.fromFile(record.file)
                        mode = ImageMode.UPSCALE
                    },
                    onUseAsSource = {
                        source = Uri.fromFile(record.file)
                        mode = ImageMode.IMG2IMG
                    },
                    onDelete = { toDelete = record },
                )
            }
            Box(Modifier.navigationBarsPadding())
        }
    }

    if (showModels) ImageModelsSheet(controller, onDismiss = { showModels = false })
    if (showHistory) {
        HistorySheet(state.history, onDismiss = { showHistory = false }) { record ->
            controller.show(record)
            showHistory = false
        }
    }
    toDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.img_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.delete(record)
                    toDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

private val ImageMode.label
    get() = when (this) {
        ImageMode.TXT2IMG -> R.string.img_mode_txt2img
        ImageMode.IMG2IMG -> R.string.img_mode_img2img
        ImageMode.UPSCALE -> R.string.img_mode_upscale
    }

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledSlider(
    title: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = position, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** Quality presets, and every sampling value by hand under the custom preset. */
@Composable
private fun SamplingSettings(settings: ImageSettings, onChange: (ImageSettings) -> Unit) {
    Text(stringResource(R.string.set_preset), style = MaterialTheme.typography.titleSmall)
    Chips(ImagePreset.entries, settings.preset, { stringResource(it.label) }) { preset ->
        onChange(if (preset == ImagePreset.CUSTOM) settings.customized() else settings.copy(preset = preset))
    }
    val sampling = settings.sampling()
    if (settings.preset != ImagePreset.CUSTOM) {
        Hint(stringResource(R.string.img_preset_summary, sampling.width, sampling.height, sampling.steps))
        return
    }

    Text(stringResource(R.string.img_width), style = MaterialTheme.typography.titleSmall)
    Chips(ImageSettings.SIZE_CHOICES, settings.width, { it.toString() }) { onChange(settings.copy(width = it)) }
    Text(stringResource(R.string.img_height), style = MaterialTheme.typography.titleSmall)
    Chips(ImageSettings.SIZE_CHOICES, settings.height, { it.toString() }) { onChange(settings.copy(height = it)) }
    Hint(stringResource(R.string.img_size_hint))

    LabeledSlider(stringResource(R.string.img_steps), settings.steps.toString(), settings.steps.toFloat(), 1f..MAX_STEPS) {
        onChange(settings.copy(steps = it.roundToInt()))
    }
    LabeledSlider(stringResource(R.string.img_cfg), "%.1f".format(settings.cfgScale), settings.cfgScale, 0f..15f) {
        onChange(settings.copy(cfgScale = (it * 2).roundToInt() / 2f))
    }
    Hint(stringResource(R.string.img_cfg_hint))

    SamplerPicker(settings.sampler) { onChange(settings.copy(sampler = it)) }

    OutlinedTextField(
        value = if (settings.seed < 0) "" else settings.seed.toString(),
        onValueChange = { text ->
            val digits = text.filter(Char::isDigit).take(MAX_SEED_DIGITS)
            onChange(settings.copy(seed = digits.toLongOrNull() ?: -1))
        },
        label = { Text(stringResource(R.string.img_seed)) },
        supportingText = { Text(stringResource(R.string.img_seed_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    val maxThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, ImageSettings.MAX_THREADS)
    LabeledSlider(
        stringResource(R.string.set_threads), settings.threads.toString(),
        settings.threads.toFloat().coerceAtMost(maxThreads.toFloat()), 1f..maxThreads.toFloat(), steps = maxThreads - 2,
    ) { onChange(settings.copy(threads = it.roundToInt())) }
    Hint(stringResource(R.string.img_threads_hint))
}

@Composable
private fun SamplerPicker(selected: ImageSampler, onSelect: (ImageSampler) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.img_sampler), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(samplerLabel(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ImageSampler.entries.forEach { sampler ->
                    DropdownMenuItem(
                        text = { Text(samplerLabel(sampler)) },
                        onClick = {
                            onSelect(sampler)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** Sampler names are the same in every language; only "auto" is a word. */
@Composable
private fun samplerLabel(sampler: ImageSampler) = when (sampler) {
    ImageSampler.AUTO -> stringResource(R.string.img_sampler_auto)
    ImageSampler.EULER_A -> "Euler a"
    ImageSampler.EULER -> "Euler"
    ImageSampler.HEUN -> "Heun"
    ImageSampler.DPMPP_2M -> "DPM++ 2M"
    ImageSampler.DPMPP_2S_A -> "DPM++ 2S a"
    ImageSampler.DDIM_TRAILING -> "DDIM trailing"
    ImageSampler.LCM -> "LCM"
    ImageSampler.TCD -> "TCD"
}

private val ImagePreset.label
    get() = when (this) {
        ImagePreset.LOW -> R.string.preset_low
        ImagePreset.MEDIUM -> R.string.preset_medium
        ImagePreset.HIGH -> R.string.preset_high
        ImagePreset.CUSTOM -> R.string.preset_custom
    }

@Composable
private fun SourcePicker(source: Uri?, onPick: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, source) {
        value = source?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching { ImageIo.decode(context, uri, THUMBNAIL_PIXELS).asImageBitmap() }.getOrNull()
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        preview?.let {
            Image(
                it, contentDescription = stringResource(R.string.img_source),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        OutlinedButton(onClick = onPick) {
            Text(stringResource(if (source == null) R.string.img_pick_source else R.string.img_change_source))
        }
    }
}

@Composable
private fun ProgressCard(progress: ImageProgress, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val elapsed = DateUtils.formatElapsedTime(((now - progress.startedAt) / 1000).coerceAtLeast(0))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val what = when {
                progress.stopping -> stringResource(R.string.img_phase_stopping)
                progress.phase == ImagePhase.LOADING -> stringResource(R.string.img_phase_loading)
                progress.phase == ImagePhase.SAMPLING && progress.total > 0 ->
                    stringResource(R.string.img_phase_step, progress.done, progress.total)
                progress.phase == ImagePhase.SAMPLING -> stringResource(R.string.img_phase_starting)
                progress.phase == ImagePhase.DECODING -> stringResource(R.string.img_phase_decoding)
                progress.phase == ImagePhase.UPSCALING ->
                    stringResource(R.string.img_phase_tile, progress.done, progress.total)
                else -> stringResource(R.string.img_phase_saving)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(what, style = MaterialTheme.typography.titleSmall)
                Text(elapsed, style = MaterialTheme.typography.bodyMedium)
            }
            val counted = progress.total > 0 && !progress.stopping &&
                (progress.phase == ImagePhase.SAMPLING || progress.phase == ImagePhase.UPSCALING)
            if (counted) {
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Hint(stringResource(if (progress.stopping) R.string.img_stopping_hint else R.string.img_busy_hint))
            OutlinedButton(enabled = !progress.stopping, onClick = onStop) {
                Icon(Icons.Outlined.Stop, contentDescription = null)
                Text(stringResource(R.string.chat_stop), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Decodes [file] off the main thread, downsampled so its longer side stays near [maxSide]. */
@Composable
fun rememberPicture(file: File, maxSide: Int): ImageBitmap? {
    val picture by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    return picture
}

@Composable
private fun ResultView(
    record: ImageRecord,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onUpscale: () -> Unit,
    onUseAsSource: () -> Unit,
    onDelete: () -> Unit,
) {
    val picture = rememberPicture(record.file, RESULT_SIDE)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ratio = if (record.width > 0 && record.height > 0) record.width.toFloat() / record.height else 1f
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            picture?.let {
                Image(it, contentDescription = record.prompt, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        }
        if (record.prompt.isNotEmpty()) Text(record.prompt, style = MaterialTheme.typography.bodyMedium)
        Hint(
            if (record.mode == ImageMode.UPSCALE) {
                stringResource(R.string.img_result_upscaled, record.width, record.height, record.model)
            } else {
                stringResource(R.string.img_result_info, record.width, record.height, record.steps, record.seed, record.model)
            },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave) { Text(stringResource(R.string.img_save)) }
            OutlinedButton(onClick = onShare) { Text(stringResource(R.string.img_share)) }
            if (record.mode != ImageMode.UPSCALE) {
                OutlinedButton(onClick = onUpscale) { Text(stringResource(R.string.img_upscale)) }
                OutlinedButton(onClick = onUseAsSource) { Text(stringResource(R.string.img_use_as_source)) }
            }
            OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(history: List<ImageRecord>, onDismiss: () -> Unit, onOpen: (ImageRecord) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (history.isEmpty()) {
            Text(stringResource(R.string.img_history_empty), modifier = Modifier.padding(24.dp).navigationBarsPadding())
            return@ModalBottomSheet
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(history, key = { it.file.name }) { record ->
                val picture = rememberPicture(record.file, THUMBNAIL_SIDE)
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpen(record) },
                ) {
                    picture?.let {
                        Image(
                            it, contentDescription = record.prompt,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_STEPS = 50f
private const val MAX_SEED_DIGITS = 9
private const val RESULT_SIDE = 2048
private const val THUMBNAIL_SIDE = 256
private const val THUMBNAIL_PIXELS = 256L * 256
