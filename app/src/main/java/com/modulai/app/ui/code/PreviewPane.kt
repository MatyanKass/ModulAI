package com.modulai.app.ui.code

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.modulai.app.R
import com.modulai.app.code.CodeController
import com.modulai.app.code.CodeState
import com.modulai.core.code.Project
import com.modulai.core.code.ProjectDir
import java.io.ByteArrayInputStream

/**
 * Each project is served from its own made-up https origin, so pages of different projects do not
 * share storage. `.invalid` never resolves, so nothing of it can leak to a real server.
 */
private fun previewHost(project: Project) = "${project.id}.preview.invalid"

/** Serves the project's files for the preview origin; any other address goes to the network as usual. */
private class ProjectWebClient(private val host: String, private val dir: ProjectDir) : WebViewClient() {
    // Called on a WebView worker thread, so reading the file here does not block the UI.
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != host) return null
        val path = url.path.orEmpty().trimStart('/').let { if (it.isEmpty() || it.endsWith("/")) it + "index.html" else it }
        // resolve() confines the path to the project, whatever the page asked for.
        val file = runCatching { dir.resolve(path) }.getOrNull()?.takeIf { it.isFile }
            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_CACHE, ByteArrayInputStream(ByteArray(0)))
        val type = mimeType(path)
        val encoding = if (type.startsWith("text/") || type.endsWith("json") || type.endsWith("xml")) "utf-8" else null
        return WebResourceResponse(type, encoding, 200, "OK", NO_CACHE, file.inputStream())
    }

    /** Links leading away from the project are not followed: the preview is not a browser. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != host

    private companion object {
        val NO_CACHE = mapOf("Cache-Control" to "no-store")
    }
}

private fun mimeType(path: String): String = when (val extension = path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "js", "mjs" -> "text/javascript"
    "css" -> "text/css"
    "json", "map" -> "application/json"
    "svg" -> "image/svg+xml"
    "wasm" -> "application/wasm"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PreviewPane(controller: CodeController, state: CodeState, project: Project) {
    val entry = remember(state.files) {
        val pages = state.files.map { it.path }.filter { it.endsWith(".html", true) || it.endsWith(".htm", true) }
        pages.find { it.equals("index.html", true) } ?: pages.minByOrNull { it.count { c -> c == '/' } }
    }
    val dir = remember(project.id) { controller.projectDir() }
    if (entry == null || dir == null) {
        Text(
            stringResource(R.string.code_preview_none),
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val context = LocalContext.current
    val host = previewHost(project)
    var console by remember(project.id) { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    val webView = remember(project.id) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The page gets the project's files through the client below and nothing else of the app.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = ProjectWebClient(host, dir)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        val source = Uri.parse(message.sourceId()).lastPathSegment.orEmpty()
                        console = "$source:${message.lineNumber()}: ${message.message()}"
                    }
                    return true
                }
            }
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    // Shows the current files whenever they change, the page is switched or reload is asked for.
    LaunchedEffect(webView, entry, state.revision, reloads) {
        console = null
        webView.loadUrl(Uri.Builder().scheme("https").authority(host).path(entry).build().toString())
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { reloads++ }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.code_reload)) }
        }
        HorizontalDivider()
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
        console?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
