package com.modulai.app

import com.modulai.core.models.DownloadProgress
import com.modulai.core.models.Downloader
import com.modulai.core.models.HfLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ActiveDownload(val fileName: String, val progress: DownloadProgress?, val error: String? = null)

/** One download at a time, owned by the app scope so it survives leaving the screen. */
class ModelDownloads(private val container: AppContainer) {
    private val _active = MutableStateFlow<ActiveDownload?>(null)
    val active: StateFlow<ActiveDownload?> = _active

    /** Bumped whenever the set of files on disk changes. */
    val revision = MutableStateFlow(0)

    private var job: Job? = null

    fun start(file: HfLink.File) {
        if (job?.isActive == true) return
        val target = container.models.fileFor(file.fileName)
        _active.value = ActiveDownload(target.name, null)
        job = container.scope.launch {
            try {
                Downloader.download(file.downloadUrl, target).collect {
                    _active.value = ActiveDownload(target.name, it)
                }
                _active.value = null
                revision.value++
            } catch (e: CancellationException) {
                _active.value = null
                throw e
            } catch (e: Exception) {
                // The .part file stays, so starting the same download again resumes it.
                _active.value = ActiveDownload(target.name, null, e.message ?: e.toString())
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _active.value = null
    }
}
