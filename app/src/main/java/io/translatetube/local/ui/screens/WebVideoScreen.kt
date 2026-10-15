package io.translatetube.local.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
    var pageLoading by remember(initialUrl) { mutableStateOf(WebMediaUrlPolicy.normalizePageUrl(initialUrl) != null) }
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val screenActive = remember(initialUrl) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val pageGeneration = remember(initialUrl) { java.util.concurrent.atomic.AtomicLong(0L) }
    val ytDlpRunGeneration = remember(initialUrl) { java.util.concurrent.atomic.AtomicLong(0L) }
    val fullScreenContainer = remember { mutableStateOf<FrameLayout?>(null) }
    val fullScreenCallback = remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var ytDlpJob by remember(initialUrl) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ---- yt-dlp 兜底解析：网页直链/HLS 检测不到时，交给 yt-dlp 处理（B站/TikTok/Twitter 等） ----
    val appCtx = LocalContext.current.applicationContext
    val ytScope = rememberCoroutineScope()
    var ytDlpBusy by remember(initialUrl) { mutableStateOf(false) }
    var ytDlpAutoAttempts by remember(initialUrl) { mutableStateOf(0) }
    var ytDlpHint by remember(initialUrl) { mutableStateOf<String?>(null) }
    var pageLoadToken by remember(initialUrl) { mutableStateOf(0) }

    fun hideFullScreenView() {
        val container = fullScreenContainer.value
        val callback = fullScreenCallback.value
        // Clear state before invoking the WebChromeClient callback. Some WebViews immediately
        // re-enter onHideCustomView(), so a callback must only be delivered once.
        fullScreenContainer.value = null
        fullScreenCallback.value = null
        container?.let { (it.parent as? ViewGroup)?.removeView(it) }
        runCatching { callback?.onCustomViewHidden() }
            .onFailure { Log.w(TAG_WEB, "Fullscreen exit callback failed", it) }
    }

    // Physical/system back should dismiss fullscreen first; it must not leave the Activity trapped
    // inside the site's custom player view.
    BackHandler(enabled = fullScreenContainer.value != null) {
        hideFullScreenView()
    }

    fun cancelYtDlp() {
        ytDlpRunGeneration.incrementAndGet()
        ytDlpJob?.cancel(CancellationException("用户取消视频解析"))
        ytDlpJob = null
        ytDlpBusy = false
        ytDlpHint = null
        ytDlpAutoAttempts = 1
        status = "已取消视频解析"
    }

    fun cancelWebParsing() {
        val wasLoading = pageLoading
        val view = webViewState.value
        if (wasLoading) runCatching { view?.stopLoading() }
        if (ytDlpBusy || ytDlpJob != null) cancelYtDlp()
        pageLoading = false
        ytDlpAutoAttempts = 1
        runCatching {
            view?.evaluateJavascript(
                "(function(){if(window.__elephantVideoProbeTimer){clearInterval(window.__elephantVideoProbeTimer);window.__elephantVideoProbeTimer=null;}})();",
                null,
            )
        }
        status = if (wasLoading) "已停止网页加载和视频解析" else "已取消视频解析"
    }

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
        val runGeneration = ytDlpRunGeneration.incrementAndGet()
        val job = ytScope.launch(Dispatchers.IO) {
            try {
                // 透传 WebView 里的站点 Cookie（登录态），提高需登录站点的解析成功率
                val cookieHeader = runCatching {
                    CookieManager.getInstance().getCookie(target)
                }.getOrNull()
                val media = io.translatetube.local.yt.YtDlpRepo.resolve(appCtx, target, cookieHeader)
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != requestGeneration ||
                        ytDlpRunGeneration.get() != runGeneration || currentPage != target
                    ) return@post
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
                    if (!screenActive.get() || pageGeneration.get() != requestGeneration ||
                        ytDlpRunGeneration.get() != runGeneration || currentPage != target
                    ) return@post
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
        ytDlpRunGeneration.incrementAndGet()
        ytDlpJob?.cancel()
        ytDlpJob = null
        ytDlpBusy = false
        ytDlpAutoAttempts = 0
        ytDlpHint = null
        pageLoading = true
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
            ytDlpRunGeneration.incrementAndGet()
            hideFullScreenView()
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

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewState.value = this
                        isClickable = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnTouchListener { touchedView, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN,
                                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                                    touchedView.parent?.requestDisallowInterceptTouchEvent(true)
                                android.view.MotionEvent.ACTION_UP,
                                android.view.MotionEvent.ACTION_CANCEL ->
                                    touchedView.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            // Observe only: return false so the webpage still receives the original tap.
                            false
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.loadsImagesAutomatically = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(false)
                        settings.setSupportMultipleWindows(false)
                        addJavascriptInterface(bridge, "ElephantVideoBridge")
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                val activity = ctx.findActivity()
                                val decor = activity?.window?.decorView as? ViewGroup
                                if (view == null || decor == null || fullScreenContainer.value != null) {
                                    callback?.onCustomViewHidden()
                                    return
                                }
                                val density = ctx.resources.displayMetrics.density
                                val container = FrameLayout(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    addView(
                                        view,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                    // The site draws two competing chevrons in the fullscreen
                                    // header on some players. A native button covers that hit area
                                    // and provides a reliable way back to the embedded player.
                                    val nativeBack = android.widget.TextView(ctx).apply {
                                        text = "‹"
                                        textSize = 32f
                                        setTextColor(android.graphics.Color.WHITE)
                                        gravity = android.view.Gravity.CENTER
                                        contentDescription = "退出全屏"
                                        background = android.graphics.drawable.GradientDrawable().apply {
                                            shape = android.graphics.drawable.GradientDrawable.OVAL
                                            // Opaque background intentionally covers the site's duplicate
                                            // left-chevron controls so the fullscreen header shows one working button.
                                            setColor(android.graphics.Color.BLACK)
                                        }
                                        isClickable = true
                                        isFocusable = true
                                        setOnClickListener { hideFullScreenView() }
                                    }
                                    addView(
                                        nativeBack,
                                        FrameLayout.LayoutParams(
                                            (30 * density).toInt(),
                                            (30 * density).toInt(),
                                            android.view.Gravity.TOP or android.view.Gravity.START,
                                        ).apply {
                                            leftMargin = (8 * density).toInt()
                                            topMargin = (6 * density).toInt()
                                        },
                                    )
                                }
                                fullScreenContainer.value = container
                                fullScreenCallback.value = callback
                                decor.addView(
                                    container,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                container.bringToFront()
                            }

                            override fun onHideCustomView() {
                                hideFullScreenView()
                            }

                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                Log.d(TAG_WEB, "Web console ${message.messageLevel()}: ${message.message()}")
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                resetForNavigation(url)
                                pageLoadToken++
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                if (!screenActive.get() || url != currentPage) return
                                pageLoading = false
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
                                            pageLoading = false
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

            }

            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pageLoading || ytDlpBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        if (ytDlpBusy) "正在解析视频资源…" else "正在加载网页并检测视频…",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    TextButton(onClick = { cancelWebParsing() }) {
                        Text(if (ytDlpBusy) "取消解析" else "停止加载")
                    }
                }
            }
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

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
}

private val PROBE_SCRIPT = """
(function() {
  try {
    if (window.__elephantVideoProbeTimer) clearInterval(window.__elephantVideoProbeTimer);

    // Embedded players often use compact icon-only controls. Expand only small
    // controls that look interactive; never synthesize a click on the user's behalf.
    function enhanceTouchTargets(root) {
      if (!root || !root.querySelectorAll) return;
      try {
        var selector = 'button,[role="button"],a[href],[onclick],[aria-label],input[type="button"],input[type="submit"],[class*="control"],[class*="Control"],[class*="icon"],[class*="Icon"],[class*="btn"],[class*="Btn"],[class*="back"],[class*="Back"],[class*="left"],[class*="Left"],[class*="arrow"],[class*="Arrow"],[class*="fanhui"],[title*="返回"],[aria-label*="返回"]';
        var nodes = root.querySelectorAll(selector);
        for (var i = 0; i < nodes.length && i < 500; i++) {
          var el = nodes[i];
          if (!el || el.__elephantTouchTargetPatched) continue;
          if (el.closest && el.closest('[aria-hidden="true"]')) continue;
          var rect = el.getBoundingClientRect();
          if (!rect || rect.width <= 0 || rect.height <= 0 || rect.width > 90 || rect.height > 90) continue;
          var classes = String(el.className || '') + ' ' + String(el.id || '') + ' ' +
            String(el.title || '') + ' ' + String(el.getAttribute('aria-label') || '');
          var isControl = el.tagName === 'BUTTON' ||
            el.getAttribute('role') === 'button' ||
            el.hasAttribute('onclick') ||
            el.hasAttribute('aria-label') ||
            /control|button|btn|icon|action|back|return|prev|arrow|chevron|jiantou|fanhui|left|返回|上一页|share|vip|fullscreen|full-screen|play|pause|volume|menu|progress/i.test(classes);
          if (!isControl) continue;
          el.style.setProperty('touch-action', 'manipulation', 'important');
          el.style.setProperty('-webkit-tap-highlight-color', 'transparent', 'important');
          el.style.setProperty('box-sizing', 'border-box', 'important');

          // The site's upper-left "back/episode list" icon can sit under a transparent video
          // overlay even while neighbouring controls work. Only promote a visible, enabled
          // back-like target at the top-left of the WebView; do not alter unrelated controls.
          var descriptor = classes;
          var ancestor = el.parentElement;
          for (var depth = 0; ancestor && depth < 3; depth++, ancestor = ancestor.parentElement) {
            descriptor += ' ' + String(ancestor.className || '') + ' ' + String(ancestor.id || '') +
              ' ' + String(ancestor.title || '') + ' ' + String(ancestor.getAttribute('aria-label') || '');
          }
          var isBackControl = /back|return|prev|previous|arrow|chevron|jiantou|fanhui|left|返回|上一页|上一级|返回列表/i.test(descriptor);
          var enabled = !el.disabled && el.getAttribute('aria-disabled') !== 'true';
          if (isBackControl && enabled && rect.top >= 0 && rect.top < 150 && rect.left >= 0 && rect.left < 150) {
            var clickable = el.closest && el.closest('button,a[href],[role="button"],[onclick],[tabindex]');
            clickable = clickable || el;
            if (!clickable.disabled && clickable.getAttribute('aria-disabled') !== 'true') {
              clickable.style.setProperty('pointer-events', 'auto', 'important');
              clickable.style.setProperty('position', 'relative', 'important');
              clickable.style.setProperty('z-index', '2147483000', 'important');
              clickable.style.setProperty('touch-action', 'manipulation', 'important');
            }
          }
          el.__elephantTouchTargetPatched = true;
        }
        // Same-origin frames can be enhanced; cross-origin frames remain protected by WebView.
        var frames = root.querySelectorAll('iframe');
        for (var j = 0; j < frames.length && j < 40; j++) {
          try {
            if (frames[j].contentDocument) enhanceTouchTargets(frames[j].contentDocument);
          } catch (ignoredFrame) {}
        }
      } catch (ignoredTargets) {}
    }

    function collect() {
      try {
        enhanceTouchTargets(document);
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
