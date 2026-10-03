package cn.edu.bjtu.mis.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.bjtu.mis.ui.components.LoadState
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val PluginReadmeDocumentOrigin = "https://plugin-readme.invalid/"
private const val PluginReadmeDocumentHost = "plugin-readme.invalid"
private const val PluginReadmeMaxImageBytes = 3L * 1024L * 1024L

internal data class PluginReadmeTarget(
    val title: String,
    val owner: String,
    val repository: String,
    val commitSha: String,
    val requirements: List<String> = emptyList(),
)

@Composable
internal fun PluginReadmePreviewDialog(
    target: PluginReadmeTarget,
    state: LoadState<String?>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(12.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val requirementsMaxHeight = maxHeight * 0.3f
                Column(modifier = Modifier.fillMaxSize()) {
                    var showRequirements by remember(target.owner, target.repository, target.commitSha) {
                        mutableStateOf(false)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 14.dp, end = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                target.title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${target.owner}/${target.repository}@${target.commitSha.take(8)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭 README 预览")
                        }
                    }
                    HorizontalDivider()
                    if (target.requirements.isNotEmpty()) {
                        TextButton(
                            onClick = { showRequirements = !showRequirements },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (showRequirements) "收起权限与运行要求" else "展开权限与运行要求") }
                        if (showRequirements) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = requirementsMaxHeight)
                                    .testTag("plugin-readme-requirements"),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                            ) {
                                items(target.requirements) { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("plugin-readme-content"),
                    ) {
                        when (state) {
                            LoadState.Loading -> PluginReadmeLoading()
                            is LoadState.Error -> PluginReadmeError(state.message, onRetry)
                            is LoadState.Data -> {
                                val markdown = state.value
                                if (markdown == null) {
                                    PluginReadmeEmpty()
                                } else {
                                    val html = remember(markdown, target.owner, target.repository, target.commitSha) {
                                        renderPluginReadmeHtml(
                                            markdown = markdown,
                                            owner = target.owner,
                                            repository = target.repository,
                                            commitSha = target.commitSha,
                                        )
                                    }
                                    PluginReadmeWebView(html = html)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginReadmeLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PluginReadmeEmpty() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "该固定版本未提供 README，仍可返回安装确认继续操作。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PluginReadmeError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("无法加载 README", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun PluginReadmeWebView(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = PluginReadmeWebViewClient(context)
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(PluginReadmeDocumentOrigin, html, "text/html", "UTF-8", null)
            }
        },
    )
}

private class PluginReadmeWebViewClient(
    private val context: Context,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.host == PluginReadmeDocumentHost) return true
        if (request.isForMainFrame && uri.scheme?.lowercase() == "https") {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return blockedResponse(403, "Forbidden")
        return PluginReadmeImageLoader.load(request.url)
    }
}

private object PluginReadmeImageLoader {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .build()

    fun load(uri: Uri): WebResourceResponse {
        val url = uri.toString()
        if (!isAllowedPluginReadmeImageUrl(url)) return blockedResponse(403, "Forbidden")
        return try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "image/*")
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return blockedResponse(response.code, "Image Unavailable")
                val body = response.body ?: return blockedResponse(502, "Empty Image")
                val mimeType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    ?.takeIf { it.startsWith("image/") }
                    ?: return blockedResponse(415, "Unsupported Media Type")
                if (body.contentLength() > PluginReadmeMaxImageBytes) {
                    return blockedResponse(413, "Image Too Large")
                }
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size().toLong() + read > PluginReadmeMaxImageBytes) {
                            return blockedResponse(413, "Image Too Large")
                        }
                        output.write(buffer, 0, read)
                    }
                }
                WebResourceResponse(
                    mimeType,
                    null,
                    200,
                    "OK",
                    SecurityHeaders,
                    ByteArrayInputStream(output.toByteArray()),
                )
            }
        } catch (_: IOException) {
            blockedResponse(502, "Image Unavailable")
        }
    }
}

private fun blockedResponse(status: Int, reason: String): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    status,
    reason,
    SecurityHeaders,
    ByteArrayInputStream(reason.toByteArray()),
)

private val SecurityHeaders = mapOf(
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
)
