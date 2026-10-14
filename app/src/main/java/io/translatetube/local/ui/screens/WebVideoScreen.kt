package io.translatetube.local.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.translatetube.local.web.WebMediaUrlPolicy
import org.json.JSONArray

private data class WebVideoCandidate(
    val url: String,
    val title: String,
    val poster: String,
    val durationSeconds: Double,
    val drmProtected: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebVideoScreen(
    initialUrl: String,
    onBack: () -> Unit,
    onOpenMedia: (mediaUrl: String, title: String, referrer: String, cookie: String?) -> Unit,
) {
    var address by remember(initialUrl) { mutableStateOf(initialUrl) }
    var currentPage by remember(initialUrl) { mutableStateOf(initialUrl) }
    val candidatesState = remember { mutableStateOf<List<WebVideoCandidate>>(emptyList()) }
    var pageTitle by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("正在打开网页并检测 HTML5 视频…") }
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun reportVideos(payload: String) {
                val found = runCatching {
                    val array = JSONArray(payload)
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val url = item.optString("url").trim()
                        if (url.isEmpty() || url == "null") return@mapNotNull null
                        WebVideoCandidate(
                            url = url,
                            title = item.optString("title"),
                            poster = item.optString("poster"),
                            durationSeconds = item.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0,
                            drmProtected = item.optBoolean("drm", false),
                        )
                    }.distinctBy { it.url }
                }.getOrDefault(emptyList())
                mainHandler.post {
                    candidatesState.value = found
                    status = if (found.isEmpty()) {
                        "网页已加载，但暂未检测到可读取的 HTML5 视频。DRM、blob/MSE 或封闭式播放器可能无法提取。"
                    } else {
                        "发现 ${found.size} 个视频源；选择可独立访问的媒体地址后即可进入配音流程。"
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.let { view ->
                runCatching { view.stopLoading() }
                runCatching { view.removeJavascriptInterface("ElephantVideoBridge") }
                runCatching { view.loadUrl("about:blank") }
                runCatching { view.destroy() }
            }
            webViewState.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网页视频", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewState.value?.reload(); status = "正在重新检测视频…" }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新网页")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("输入网页地址") },
                )
                IconButton(onClick = {
                    val normalized = WebMediaUrlPolicy.normalizePageUrl(address)
                    if (normalized == null) {
                        status = "网页地址无效，请输入 HTTP/HTTPS 链接。"
                    } else {
                        address = normalized
                        currentPage = normalized
                        candidatesState.value = emptyList()
                        status = "正在打开网页并检测 HTML5 视频…"
                        webViewState.value?.loadUrl(normalized)
                    }
                }) {
                    Icon(Icons.Filled.ArrowForwardIos, contentDescription = "打开网页")
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewState.value = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.loadsImagesAutomatically = true
                        addJavascriptInterface(bridge, "ElephantVideoBridge")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                currentPage = url
                                address = url
                                pageTitle = view.title.orEmpty()
                                status = "网页已加载，正在检测视频源…"
                                view.evaluateJavascript(PROBE_SCRIPT, null)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame) {
                                    mainHandler.post {
                                        status = "网页加载失败：${error.description}"
                                    }
                                }
                            }
                        }
                        val initial = WebMediaUrlPolicy.normalizePageUrl(initialUrl)
                        if (initial != null) loadUrl(initial) else status = "网页地址无效，请返回后检查链接。"
                    }
                },
                update = { },
            )

            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (candidatesState.value.isNotEmpty()) {
                Text("检测到的视频", style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(candidatesState.value, key = { it.url }) { candidate ->
                        val reason = when {
                            candidate.drmProtected -> "检测到 EME/DRM 标记，不能提取或绕过受保护的媒体音轨。"
                            else -> WebMediaUrlPolicy.unsupportedReason(candidate.url)
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    candidate.title.ifBlank { pageTitle.ifBlank { "网页视频" } },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(candidate.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (reason != null) {
                                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                                Button(
                                    enabled = reason == null,
                                    onClick = {
                                        val mediaUrl = WebMediaUrlPolicy.normalizeMediaUrl(candidate.url)
                                        if (mediaUrl == null) {
                                            status = WebMediaUrlPolicy.unsupportedReason(candidate.url)
                                                ?: "无法读取该媒体地址。"
                                        } else {
                                            val cookie = runCatching { CookieManager.getInstance().getCookie(mediaUrl) }.getOrNull()
                                            onOpenMedia(mediaUrl, candidate.title.ifBlank { pageTitle }, currentPage, cookie)
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("用大象翻译播放")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val PROBE_SCRIPT = """
(function() {
  try {
    if (window.__elephantVideoProbeTimer) clearInterval(window.__elephantVideoProbeTimer);
    function collect() {
      try {
        var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
        var rows = videos.map(function(v) {
          var src = v.currentSrc || v.src || '';
          if (!src) {
            var source = v.querySelector('source[src]');
            if (source) src = source.src || source.getAttribute('src') || '';
          }
          return {
            url: src,
            title: document.title || '',
            poster: v.poster || '',
            duration: isFinite(v.duration) ? v.duration : 0,
            drm: !!v.mediaKeys
          };
        }).filter(function(row) { return !!row.url; });
        if (window.ElephantVideoBridge) {
          window.ElephantVideoBridge.reportVideos(JSON.stringify(rows));
        }
      } catch (ignored) {}
    }
    collect();
    window.__elephantVideoProbeTimer = setInterval(collect, 1500);
    document.addEventListener('loadedmetadata', collect, true);
    document.addEventListener('play', collect, true);
  } catch (ignored) {}
})();
""".trimIndent()
