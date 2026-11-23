package com.modulai.app

import android.content.Context
import com.modulai.core.models.DownloadProgress
import com.modulai.core.models.Downloader
import com.modulai.core.models.HfLink
import com.modulai.core.models.HttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class DownloadState { WAITING, RUNNING, FAILED }

data class DownloadItem(
    val id: Long,
    val file: HfLink.File,
    val target: File,
    val state: DownloadState = DownloadState.WAITING,
    val progress: DownloadProgress? = null,
    val error: String? = null,
    /** Hugging Face refused access: the model is gated and needs a token. */
    val accessDenied: Boolean = false,
) {
    val fileName: String get() = target.name
}

/** The Models tab's entry point to [DownloadQueue]; resolves where a file is stored. */
class ModelDownloads(private val container: AppContainer) {
    val queue: StateFlow<List<DownloadItem>> = DownloadQueue.items

    /** Bumped whenever the set of files on disk changes. */
    val revision = DownloadQueue.revision

    fun enqueue(context: Context, file: HfLink.File) =
        DownloadQueue.enqueue(context, file, container.models.fileFor(file.fileName))

    /** Drops a queued, running or failed download together with its partial data. */
    fun cancel(item: DownloadItem) = DownloadQueue.cancel(item)
}

/**
 * Downloads waiting for their turn, processed one at a time.
 *
 * Process-wide rather than owned by AppContainer: the activity builds a new container when
 * it is recreated, and the foreground service has to see the same queue as the UI.
 * Everything here runs on the main thread.
 */
object DownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items
    val revision = MutableStateFlow(0)

    private var nextId = 0L
    private var worker: Job? = null
    private var running: Pair<Long, Job>? = null

    /** Adds [file] to the queue; a failed download of the same file is retried, resuming its partial data. */
    fun enqueue(context: Context, file: HfLink.File, target: File) {
        val existing = _items.value.find { it.target == target }
        if (existing != null && existing.state != DownloadState.FAILED) return
        _items.value = _items.value.filterNot { it.target == target } + DownloadItem(nextId++, file, target)

        val app = context.applicationContext
        DownloadService.start(app)
        if (worker?.isActive != true) worker = scope.launch { drain(app) }
    }

    fun cancel(item: DownloadItem) {
        _items.value = _items.value.filterNot { it.id == item.id }
        val active = running
        // A running transfer removes its own partial file once it has let go of it.
        if (active?.first == item.id) active.second.cancel() else Downloader.partFile(item.target).delete()
    }

    private suspend fun drain(context: Context) {
        while (true) {
            val next = _items.value.firstOrNull { it.state == DownloadState.WAITING } ?: break
            val job = scope.launch { transfer(context, next) }
            running = next.id to job
            job.join()
        }
        running = null
    }

    private suspend fun transfer(context: Context, item: DownloadItem) {
        update(item.id) { it.copy(state = DownloadState.RUNNING) }
        try {
            Downloader.download(item.file.downloadUrl, item.target, ModelPrefs(context).hfToken).collect { p ->
                update(item.id) { it.copy(progress = p) }
            }
            _items.value = _items.value.filterNot { it.id == item.id }
            revision.value++
        } catch (e: CancellationException) {
            Downloader.partFile(item.target).delete()
            throw e
        } catch (e: Exception) {
            // The .part file stays, so retrying resumes the download.
            update(item.id) {
                it.copy(
                    state = DownloadState.FAILED,
                    progress = null,
                    error = e.message ?: e.toString(),
                    accessDenied = e is HttpStatusException && e.accessDenied,
                )
            }
        }
    }

    private fun update(id: Long, change: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) change(it) else it }
    }
}
