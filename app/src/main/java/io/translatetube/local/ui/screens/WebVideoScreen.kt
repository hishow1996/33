package io.translatetube.local.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.translatetube.local.web.WebMediaUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

private const val TAG_WEB = "WebVideoScreen"

internal data class WebVideoCandidate(
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
    val screenActive = remember(initialUrl) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val pageGeneration = remember(initialUrl) { java.util.concurrent.atomic.AtomicLong(0L) }
    var ytDlpJob by remember(initialUrl) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ---- yt-dlp 兜底解析：网页直链/HLS 检测不到时，交给 yt-dlp 处理（B站/TikTok/Twitter 等） ----
    val appCtx = LocalContext.current.applicationContext
    val ytScope = rememberCoroutineScope()
    var ytDlpBusy by remember(initialUrl) { mutableStateOf(false) }
    var ytDlpAutoAttempts by remember(initialUrl) { mutableStateOf(0) }
    var ytDlpHint by remember(initialUrl) { mutableStateOf<String?>(null) }
    var pageLoadToken by remember(initialUrl) { mutableStateOf(0) }

    fun runYtDlp(target: String) {
        if (ytDlpBusy) return
        if (!screenActive.get() || !io.translatetube.local.yt.YtDlpRepo.isEligible(target)) {
            ytDlpHint = "yt-dlp 仅适用于 http/https 非 YouTube 链接"
            return
        }
        ytDlpBusy = true
        ytDlpHint = null
        status = "正在用 yt-dlp 解析该网站视频（首次使用需解包运行时，请稍候）…"
        val requestGeneration = pageGeneration.get()
        val job = ytScope.launch(Dispatchers.IO) {
            try {
                // 透传 WebView 里的站点 Cookie（登录态），提高需登录站点的解析成功率
                val cookieHeader = runCatching {
                    CookieManager.getInstance().getCookie(target)
                }.getOrNull()
                val media = io.translatetube.local.yt.YtDlpRepo.resolve(appCtx, target, cookieHeader)
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != requestGeneration || currentPage != target) {
                        return@post
                    }
                    ytDlpBusy = false
                    ytDlpJob = null
                    status = "yt-dlp 解析成功：${media.title}"
                    onOpenMedia(
                        media.mediaUrl,
                        media.title.ifBlank { pageTitle },
                        media.referrer,
                        media.cookie.takeIf { it.isNotBlank() },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_WEB, "yt-dlp resolve failed for $target", e)
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != requestGeneration || currentPage != target) {
                        return@post
                    }
                    ytDlpBusy = false
                    ytDlpJob = null
                    ytDlpHint = io.translatetube.local.yt.YtDlpRepo.failureHint(e)
                    status = "yt-dlp 解析未成功；可稍后重试或改用其他链接。"
                }
            }
        }
        ytDlpJob = job
    }

    fun resetForNavigation(target: String) {
        pageGeneration.incrementAndGet()
        ytDlpJob?.cancel()
        ytDlpJob = null
        ytDlpBusy = false
        ytDlpAutoAttempts = 0
        ytDlpHint = null
        candidatesState.value = emptyList()
        currentPage = target
        address = target
        pageTitle = ""
        status = "正在打开网页并检测 HTML5 视频…"
    }

    // 页面加载后仍无任何候选媒体时，自动尝试一次 yt-dlp 解析。
    LaunchedEffect(currentPage, pageLoadToken) {
        delay(5000)
        if (candidatesState.value.isEmpty() && !ytDlpBusy && ytDlpAutoAttempts == 0) {
            ytDlpAutoAttempts = 1
            runYtDlp(currentPage)
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun reportVideos(payload: String) {
                // The bridge is callable by page JavaScript. Bound parsing and retained rows so a
                // page with thousands of video elements cannot grow the native heap indefinitely.
                if (!WebVideoProbePolicy.acceptsPayload(payload)) {
                    Log.w(TAG_WEB, "Ignoring empty or oversized video probe payload")
                    return
                }
                val found = runCatching {
                    val array = JSONArray(payload)
                    (0 until minOf(array.length(), WebVideoProbePolicy.MAX_ROWS_PER_PAYLOAD)).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val url = item.optString("url").trim()
                        if (!WebVideoProbePolicy.acceptsMediaUrl(url)) return@mapNotNull null
                        WebVideoCandidate(
                            url = url,
                            title = WebVideoProbePolicy.normalizeTitle(item.optString("title")),
                            poster = WebVideoProbePolicy.normalizePoster(item.optString("poster")),
                            durationSeconds = item.optDouble("duration", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                            drmProtected = item.optBoolean("drm", false),
                        )
                    }.distinctBy { it.url }
                }.getOrDefault(emptyList())
                val bridgeGeneration = pageGeneration.get()
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != bridgeGeneration) return@post
                    val merged = WebVideoProbePolicy.mergeCandidates(candidatesState.value, found)
                    candidatesState.value = merged
                    status = if (merged.isEmpty()) {
                        "网页已加载，但暂未检测到媒体地址。DRM、blob/MSE 或封闭式播放器可能无法提取。"
                    } else {
                        "发现 ${merged.size} 个媒体地址；选择可直接访问且未受 DRM 保护的源即可继续。"
                    }
                }
            }
        }
    }

    DisposableEffect(screenActive) {
        onDispose {
            screenActive.set(false)
            ytDlpJob?.cancel()
            ytDlpJob = null
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
                        resetForNavigation(normalized)
                        pageLoadToken++
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
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                resetForNavigation(url)
                                pageLoadToken++
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                if (!screenActive.get() || url != currentPage) return
                                address = url
                                pageTitle = view.title.orEmpty()
                                status = "网页已加载，正在检测视频源…"
                                view.evaluateJavascript(PROBE_SCRIPT, null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): android.webkit.WebResourceResponse? {
                                val resourceUrl = request.url.toString()
                                val requestGeneration = pageGeneration.get()
                                if (WebVideoProbePolicy.acceptsMediaUrl(resourceUrl) &&
                                    WebMediaUrlPolicy.looksLikeMediaRequest(resourceUrl)
                                ) {
                                    mainHandler.post {
                                        if (!screenActive.get() || pageGeneration.get() != requestGeneration) {
                                            return@post
                                        }
                                        val candidate = WebVideoCandidate(
                                            url = resourceUrl,
                                            title = WebVideoProbePolicy.normalizeTitle(pageTitle),
                                            poster = "",
                                            durationSeconds = 0.0,
                                            drmProtected = false,
                                        )
                                        candidatesState.value = WebVideoProbePolicy.mergeCandidates(
                                            candidatesState.value,
                                            listOf(candidate),
                                        )
                                        status = "已从网页网络请求中检测到媒体资源。"
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame) {
                                    mainHandler.post {
                                        if (screenActive.get() && request.url.toString() == currentPage) {
                                            status = "网页加载失败：${error.description}"
                                        }
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
            if (ytDlpHint != null) {
                Text(
                    ytDlpHint.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (io.translatetube.local.yt.YtDlpRepo.isEligible(currentPage)) {
                    var updatingRules by remember(currentPage) { mutableStateOf(false) }
                    TextButton(
                        enabled = !updatingRules && !ytDlpBusy,
                        onClick = {
                            updatingRules = true
                            ytDlpHint = null
                            status = "正在更新 yt-dlp 解析规则（约 30MB，请稍候）…"
                            ytScope.launch(Dispatchers.IO) {
                                try {
                                    val message = io.translatetube.local.yt.YtDlpRepo.updateRules(appCtx)
                                    mainHandler.post {
                                        updatingRules = false
                                        status = message
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    mainHandler.post {
                                        updatingRules = false
                                        ytDlpHint = "规则更新失败：${e.message ?: "网络错误"}"
                                        status = "规则更新失败，可稍后重试。"
                                    }
                                }
                            }
                        },
                    ) { Text(if (updatingRules) "更新中…" else "更新 yt-dlp 解析规则") }
                }
            }
            if (candidatesState.value.isEmpty() && WebMediaUrlPolicy.normalizePageUrl(currentPage) != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !ytDlpBusy,
                        onClick = { runYtDlp(currentPage) },
                    ) {
                        Text(if (ytDlpBusy) "yt-dlp 解析中…" else "用 yt-dlp 解析本页视频")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "支持B站、TikTok、Twitter 等上千个网站；YouTube 风控期也可用此方式兜底",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
        var videos = Array.prototype.slice.call(document.querySelectorAll('video'), 0, 120);
        var rows = videos.map(function(v) {
          var src = v.currentSrc || v.src || '';
          if (!src) {
            var source = v.querySelector('source[src]');
            if (source) src = source.src || source.getAttribute('src') || '';
          }
          if (String(src).length > 8192) return null;
          return {
            url: src,
            title: (document.title || '').slice(0, 512),
            poster: (v.poster || '').slice(0, 2048),
            duration: isFinite(v.duration) ? v.duration : 0,
            drm: !!v.mediaKeys
          };
        }).filter(function(row) { return !!row && !!row.url; });
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
