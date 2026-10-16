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
import io.translatetube.local.web.PikpakEntry
import io.translatetube.local.web.PikpakShare
import io.translatetube.local.web.WebMediaUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    /** 后台 HEAD/Range 校验确认是可访问的视频流（Content-Type 判定） */
    val verified: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebVideoScreen(
    initialUrl: String,
    onBack: () -> Unit,
    onOpenMedia: (mediaUrl: String, title: String, referrer: String, cookie: String?, userAgent: String?) -> Unit,
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
    // 自动「更新规则后重试」是否已用过（每次页面导航只自动自救一次，防循环）
    var rulesUpdatedForRetry by remember(initialUrl) { mutableStateOf(false) }
    // ---- 候选验证（参考 VBrowser 思路）：后台 HEAD/Range 请求确认候选真的是视频流，
    // 过滤广告/接口/失效地址，验证通过的 m3u8 自动进入播放。 ----
    var verifiedUrls by remember(initialUrl) { mutableStateOf(emptySet<String>()) }
    val verifierScope = remember(initialUrl) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    var manualChose by remember(initialUrl) { mutableStateOf(false) }
    var autoPlayedUrl by remember(initialUrl) { mutableStateOf<String?>(null) }
    val verifyClient = remember(initialUrl) { WebCandidateVerifier.buildClient() }

    DisposableEffect(Unit) {
        onDispose { verifierScope.cancel() }
    }

    // ---- PikPak 分享清单（mypikpak.com/s/...）：网页 hook 结构化捕获文件树 +
    // 复用网页自带凭证主动递归枚举文件夹，视频条目直接并入候选列表点播。 ----
    var pikpakShareId by remember(initialUrl) { mutableStateOf<String?>(null) }
    var pikpakPasscodeToken by remember(initialUrl) { mutableStateOf("") }
    var pikpakCapturedHeaders by remember(initialUrl) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pikpakEntries by remember(initialUrl) { mutableStateOf<List<PikpakEntry>>(emptyList()) }
    var pikpakListedParents by remember(initialUrl) { mutableStateOf(emptySet<String>()) }
    // 主动 BFS 完整枚举过的目录（与 hook 单层列出的 listedParents 区分：
    // 只有完整枚举过的子树才可在「展开全部」时跳过，避免漏掉 hook 目录的子文件夹）
    var pikpakEnumeratedParents by remember(initialUrl) { mutableStateOf(emptySet<String>()) }
    var pikpakNeedsPasscode by remember(initialUrl) { mutableStateOf(false) }
    var pikpakBusy by remember(initialUrl) { mutableStateOf(false) }
    var pikpakNotice by remember(initialUrl) { mutableStateOf<String?>(null) }
    val pikpakScope = remember(initialUrl) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    DisposableEffect(Unit) {
        onDispose { pikpakScope.cancel() }
    }

    // 监听候选变化，对未验证的候选后台校验
    LaunchedEffect(candidatesState.value) {
        val pending = candidatesState.value
            .map { it.url }
            .filter { WebMediaUrlPolicy.normalizeMediaUrl(it) != null && it !in verifiedUrls }
        pending.forEach { candidateUrl ->
            verifierScope.launch {
                val ok = runCatching {
                    WebCandidateVerifier.verify(
                        client = verifyClient,
                        url = candidateUrl,
                        referer = currentPage,
                        cookie = runCatching { CookieManager.getInstance().getCookie(candidateUrl) }.getOrNull(),
                        userAgent = webViewState.value?.settings?.userAgentString,
                    )
                }.getOrDefault(false)
                if (ok) mainHandler.post { verifiedUrls = verifiedUrls + candidateUrl }
            }
        }
    }

    // 唯一验证通过的流清单（m3u8/mpd）自动进入播放；用户手动选择后不再自动
    LaunchedEffect(verifiedUrls, manualChose) {
        if (manualChose || autoPlayedUrl != null) return@LaunchedEffect
        val manifest = candidatesState.value.firstOrNull {
            it.url in verifiedUrls && WebVideoProbePolicy.candidateRank(it.url) > 0
        } ?: return@LaunchedEffect
        delay(2500L)
        if (manualChose) return@LaunchedEffect
        autoPlayedUrl = manifest.url
        val normalized = WebMediaUrlPolicy.normalizeMediaUrl(manifest.url)
        if (normalized != null && screenActive.get()) {
            val cookie = runCatching { CookieManager.getInstance().getCookie(normalized) }.getOrNull()
            onOpenMedia(
                normalized,
                manifest.title.ifBlank { pageTitle },
                currentPage,
                cookie,
                webViewState.value?.settings?.userAgentString,
            )
        }
    }

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
                    if (rulesUpdatedForRetry) status = "更新解析规则后重试成功"
                    ytDlpBusy = false
                    ytDlpJob = null
                    status = "yt-dlp 解析成功：${media.title}"
                    onOpenMedia(
                        media.mediaUrl,
                        media.title.ifBlank { pageTitle },
                        media.referrer,
                        media.cookie.takeIf { it.isNotBlank() },
                        webViewState.value?.settings?.userAgentString,
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
                    // 自救：检测到上游有新版解析规则时，先自动更新规则，再重试一次
                    // （网站改版导致旧规则失效是最常见的解析失败原因）
                    if (io.translatetube.local.yt.YtDlpRepo.updateState.value.hasUpdate && !rulesUpdatedForRetry) {
                        rulesUpdatedForRetry = true
                        status = "检测到 yt-dlp 有新版解析规则，正在自动更新（约 30MB）…"
                        ytScope.launch(Dispatchers.IO) {
                            try {
                                io.translatetube.local.yt.YtDlpRepo.updateRules(appCtx)
                            } catch (updateError: Exception) {
                                mainHandler.post {
                                    status = "解析规则自动更新失败，可稍后在设置里手动更新后重试。"
                                }
                                return@launch
                            }
                            mainHandler.post {
                                if (screenActive.get() && pageGeneration.get() == requestGeneration && !ytDlpBusy) {
                                    status = "解析规则已更新，正在重试解析…"
                                    runYtDlp(target)
                                }
                            }
                        }
                    }
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
        // PikPak 清单跟随整页导航重置（SPA 内点文件夹不触发导航，清单保留）
        pikpakShareId = null
        pikpakPasscodeToken = ""
        pikpakCapturedHeaders = emptyMap()
        pikpakEntries = emptyList()
        pikpakListedParents = emptySet()
        pikpakEnumeratedParents = emptySet()
        pikpakNeedsPasscode = false
        pikpakBusy = false
        pikpakNotice = null
        currentPage = target
        address = target
        pageTitle = ""
        status = "正在打开网页并检测 HTML5 视频…"
    }

    // PikPak 清单条目合并：按文件 id 去重（hook 与主动枚举两来源的 parentId 可能不一致，
    // 同一文件保留信息更全的一条：有直链优先、有真实父目录优先），
    // 视频条目自动并入候选列表（标题=文件名），并把 API 刚下发的直链直接标记已验证
    // （跳过对 CDN 的批量 HEAD 校验，PikPak 清单可能上百条，逐条验证既慢又易触发限流）。
    fun mergePikpakEntries(incoming: List<PikpakEntry>) {
        val merged = (incoming + pikpakEntries)
            .groupBy { it.id }
            .map { (_, group) ->
                group.maxWithOrNull(
                    compareBy({ it.downloadUrl.isNotBlank() }, { it.parentId.isNotBlank() })
                ) ?: group.first()
            }
            .take(900)
        pikpakEntries = merged
        val videoCandidates = merged
            .filter { it.isVideo && it.downloadUrl.isNotBlank() }
            .map {
                WebVideoCandidate(
                    url = it.downloadUrl,
                    title = it.name,
                    poster = "",
                    durationSeconds = 0.0,
                    drmProtected = false,
                )
            }
        if (videoCandidates.isNotEmpty()) {
            candidatesState.value =
                WebVideoProbePolicy.mergeCandidates(candidatesState.value, videoCandidates)
            verifiedUrls = verifiedUrls + videoCandidates.map { it.url }
        }
    }

    // 主动展开 PikPak 分享目录（rootParentId="" 为整棵树；传文件夹 id 则展开该子树）。
    // 优先复用网页 hook 捕获的同款请求头；没有捕获时用自生成设备 ID 兜底。
    fun expandPikpak(rootParentId: String) {
        val shareId = pikpakShareId ?: PikpakShare.parseShareId(currentPage)
        if (shareId == null) {
            pikpakNotice = "未识别到 PikPak 分享链接。"
            return
        }
        pikpakShareId = shareId
        if (pikpakBusy) return
        pikpakBusy = true
        pikpakNotice = "正在读取 PikPak 分享清单…"
        val deviceUuid = PikpakShare.deviceIdentifier(appCtx)
        val passcodeToken = pikpakPasscodeToken.ifBlank { PikpakShare.capturedPasscodeToken }
        // 增量枚举：完整枚举过的子树跳过（点具体文件夹时强制重读该子树）
        val skipParents = if (rootParentId.isBlank()) pikpakEnumeratedParents
        else pikpakEnumeratedParents - rootParentId
        pikpakScope.launch(Dispatchers.IO) {
            val result = PikpakShare.enumerateAll(
                shareId = shareId,
                rootParentId = rootParentId,
                passcodeToken = passcodeToken,
                deviceUuid = deviceUuid,
                skipParents = skipParents,
            )
            mainHandler.post {
                if (!screenActive.get()) return@post
                pikpakBusy = false
                if (PikpakShare.capturedPasscodeToken.isNotBlank()) {
                    pikpakPasscodeToken = PikpakShare.capturedPasscodeToken
                }
                if (result.needsPasscode) pikpakNeedsPasscode = true
                if (result.entries.isNotEmpty()) {
                    pikpakListedParents = pikpakListedParents + result.visitedParents
                    pikpakEnumeratedParents = pikpakEnumeratedParents + result.visitedParents
                    mergePikpakEntries(result.entries)
                }
                pikpakNotice = when {
                    result.entries.isNotEmpty() && result.error == null ->
                        "清单已更新：共 ${pikpakEntries.count { it.isVideo }} 个视频、" +
                            "${pikpakEntries.count { it.isFolder }} 个文件夹。点下方候选或文件夹继续。"
                    result.entries.isNotEmpty() -> "部分读取成功：${result.error}"
                    result.visitedParents.isNotEmpty() && result.error == null ->
                        "此前已读取过这些目录，无新增内容。"
                    else -> result.error ?: "未读取到文件，可在网页里点开文件夹后自动读取。"
                }
            }
        }
    }

    // PikPak 分享链接：页面加载后主动读取清单（文件夹支持），不跑 yt-dlp。
    // 其他页面仍无候选时，自动尝试一次 yt-dlp 解析。
    LaunchedEffect(currentPage, pageLoadToken) {
        delay(5000)
        if (PikpakShare.parseShareId(currentPage) != null) {
            if (pikpakShareId == null && pikpakEntries.isEmpty() && !pikpakBusy) {
                expandPikpak("")
            }
            return@LaunchedEffect
        }
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

            // PikPak share API 结构化上报：文件树 + 网页同款请求头（供主动枚举复用凭证）
            @JavascriptInterface
            fun reportPikpak(payload: String) {
                val data = runCatching { PikpakShare.parseHookPayload(payload) }.getOrNull() ?: return
                val bridgeGeneration = pageGeneration.get()
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != bridgeGeneration) return@post
                    if (!data.shareId.isNullOrBlank()) pikpakShareId = data.shareId
                    if (data.headers.isNotEmpty()) pikpakCapturedHeaders = data.headers
                    if (data.passcodeToken.isNotBlank()) pikpakPasscodeToken = data.passcodeToken
                    if (data.parentId.isNotBlank()) {
                        pikpakListedParents = pikpakListedParents + data.parentId
                    }
                    if (data.api == "verify" || data.api == "share") {
                        // 提取码已在网页里通过（share 校验端点会下发 pass_code_token）
                        pikpakNeedsPasscode = false
                    }
                    if (data.entries.isNotEmpty()) mergePikpakEntries(data.entries)
                    if (data.entries.isNotEmpty()) {
                        val videoCount = pikpakEntries.count { it.isVideo }
                        val folderCount = pikpakEntries.count { it.isFolder }
                        pikpakNotice = "已从网页捕获清单：$videoCount 个视频、$folderCount 个文件夹。"
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
                                // 尽早挂上网络 hook：站点视频接口多在 DOM 就绪前就发起 XHR/fetch，
                                // 等到 onPageFinished 再注入就抓不到响应了。
                                if (screenActive.get()) {
                                    runCatching { view.evaluateJavascript(NET_HOOK_SCRIPT, null) }
                                }
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
            // ---- PikPak 分享清单区：识别到分享链接或已捕获清单时显示 ----
            if (PikpakShare.parseShareId(currentPage) != null || pikpakShareId != null) {
                val pikpakFolders = pikpakEntries.filter { it.isFolder }
                    .sortedBy { it.name.lowercase() }
                val pikpakVideos = pikpakEntries.filter { it.isVideo && it.downloadUrl.isNotBlank() }
                Text("PikPak 分享清单", style = MaterialTheme.typography.titleMedium)
                if (pikpakEntries.isEmpty()) {
                    Text(
                        "已识别 PikPak 分享链接，正在读取文件清单；若迟迟无结果，请在上方网页里点一下任意文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "共 ${pikpakVideos.size} 个视频、${pikpakFolders.size} 个文件夹。视频已加入下方候选列表，点候选卡片即可转写翻译配音。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pikpakNotice != null) {
                    Text(
                        pikpakNotice.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pikpakNeedsPasscode) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pikpakNeedsPasscode) {
                    Text(
                        "该分享需要提取码：请先在上方网页里输入提取码（会自动记住），再点「展开全部文件夹」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(enabled = !pikpakBusy, onClick = { expandPikpak("") }) {
                    Text(
                        when {
                            pikpakBusy -> "读取中…"
                            pikpakEntries.isEmpty() -> "立即读取清单"
                            else -> "展开全部文件夹"
                        }
                    )
                }
                if (pikpakFolders.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(pikpakFolders, key = { "${it.parentId}/${it.id}" }) { folder ->
                            val alreadyListed = folder.id in pikpakListedParents
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (alreadyListed) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !pikpakBusy && !alreadyListed) {
                                            expandPikpak(folder.id)
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("📁", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        folder.name.ifBlank { "文件夹" },
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (alreadyListed) "已读取" else "展开",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (alreadyListed) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (candidatesState.value.isNotEmpty()) {
                Text("检测到的视频", style = MaterialTheme.typography.titleMedium)
                // 验证通过的候选排最前（后台已确认是可播放的流），其次是 m3u8/mpd 清单
                val displayCandidates = remember(candidatesState.value, verifiedUrls) {
                    candidatesState.value
                        .mapIndexed { index, c -> Pair(c, index) }
                        .sortedWith(
                            object : Comparator<Pair<WebVideoCandidate, Int>> {
                                override fun compare(
                                    a: Pair<WebVideoCandidate, Int>,
                                    b: Pair<WebVideoCandidate, Int>,
                                ): Int {
                                    val aVerified = a.first.url in verifiedUrls
                                    val bVerified = b.first.url in verifiedUrls
                                    if (aVerified != bVerified) return if (bVerified) 1 else -1
                                    val aRank = WebVideoProbePolicy.candidateRank(a.first.url)
                                    val bRank = WebVideoProbePolicy.candidateRank(b.first.url)
                                    if (aRank != bRank) return bRank - aRank
                                    return a.second - b.second
                                }
                            },
                        )
                        .map { it.first }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(displayCandidates, key = { it.url }) { candidate ->
                        val reason = when {
                            candidate.drmProtected -> "检测到 EME/DRM 标记，不能提取或绕过受保护的媒体音轨。"
                            else -> WebMediaUrlPolicy.unsupportedReason(candidate.url)
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        candidate.title.ifBlank { pageTitle.ifBlank { "网页视频" } },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (candidate.url in verifiedUrls) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "已验证",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(candidate.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (reason != null) {
                                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                                Button(
                                    enabled = reason == null,
                                    onClick = {
                                        manualChose = true
                                        val mediaUrl = WebMediaUrlPolicy.normalizeMediaUrl(candidate.url)
                                        if (mediaUrl == null) {
                                            status = WebMediaUrlPolicy.unsupportedReason(candidate.url)
                                                ?: "无法读取该媒体地址。"
                                        } else {
                                            val cookie = runCatching { CookieManager.getInstance().getCookie(mediaUrl) }.getOrNull()
                                            onOpenMedia(
                                                mediaUrl,
                                                candidate.title.ifBlank { pageTitle },
                                                currentPage,
                                                cookie,
                                                webViewState.value?.settings?.userAgentString,
                                            )
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

private val NET_HOOK_SCRIPT = """
(function(){
  // 站点视频地址常藏在 XHR/fetch 接口响应里（yfsp/nnyy 等），URL 拦截看不到响应体，
  // 必须在页面 JS 内部抓取；播放器还常被放进同源 iframe，需对每个 frame 分别安装。
  function installHook(target) {
    try {
      if (!target || target.__elephantNetHooked) return;
      target.__elephantNetHooked = true;
      target.__elephantNetMedia = target.__elephantNetMedia || {};
      var MEDIA_RE = /https?:\/\/[^"'\s\\<>]+?\.(m3u8|mp4|mpd|flv|m4a|aac)[^"'\s\\<>]*/g;
      var MEDIA_RE_PROTOCOL_REL = /\/\/[^"'\s\\<>]+?\.(m3u8|mp4|mpd|flv)[^"'\s\\<>]*/g;
      var reportNet = function(text) {
        try {
          if (!text) return;
          var text2 = String(text);
          if (text2.length > 2097152) return;
          var found = text2.match(MEDIA_RE) || [];
          var rel = text2.match(MEDIA_RE_PROTOCOL_REL) || [];
          if (!found.length && !rel.length) return;
          var added = false;
          var addUrl = function(u) {
            if (u.indexOf('//') === 0) u = target.location.protocol + u;
            if (!target.__elephantNetMedia[u]) {
              target.__elephantNetMedia[u] = true;
              added = true;
            }
          };
          for (var i = 0; i < found.length && i < 50; i++) addUrl(found[i]);
          for (var j = 0; j < rel.length && j < 20; j++) addUrl(rel[j]);
          if (added && target.ElephantVideoBridge) {
            var rows = [];
            for (var url in target.__elephantNetMedia) {
              if (Object.prototype.hasOwnProperty.call(target.__elephantNetMedia, url) && rows.length < 100) {
                rows.push({ url: url, title: (document.title || '').slice(0, 512), poster: '', duration: 0, drm: false });
              }
            }
            target.ElephantVideoBridge.reportVideos(JSON.stringify(rows));
          }
        } catch (ignored) {}
      };
      // PikPak share API 结构化收集：把分享目录文件树 + 网页同款请求头一并上报，
      // 原生侧用它渲染文件清单并复用凭证递归展开嵌套文件夹。
      // 网页端点均为 /drive/v1/share(校验)与 /drive/v1/share/{list,detail,file_info}
      var PIKPAK_API_RE = /https?:\/\/[^\/]*mypikpak\.com\/drive\/v1\/share(?:\?|\/(?:list|detail|file_info|verify|restore))/i;
      var pikpakRow = function(f) {
        var mediaUrl = '';
        try {
          if (f.web_content_link) mediaUrl = String(f.web_content_link);
          if (!mediaUrl && f.links) {
            for (var lk in f.links) {
              if (Object.prototype.hasOwnProperty.call(f.links, lk) && f.links[lk] && f.links[lk].url) {
                mediaUrl = String(f.links[lk].url);
                break;
              }
            }
          }
          if (!mediaUrl && f.medias && f.medias.length && f.medias[0]) {
            mediaUrl = (f.medias[0].link && f.medias[0].link.url) || f.medias[0].download_url || '';
          }
        } catch (e0) {}
        return {
          id: String(f.id || ''),
          name: String(f.name || ''),
          kind: String(f.kind || ''),
          file_type: String(f.file_type || ''),
          mime_type: String(f.mime_type || ''),
          size: String(f.size || ''),
          media_url: String(mediaUrl || '')
        };
      };
      var collectPikpak = function(reqUrl, hdrs, reqBody, respText) {
        try {
          if (!reqUrl) return;
          var url2 = String(reqUrl);
          if (url2.charAt(0) === '/') url2 = target.location.origin + url2;
          if (!PIKPAK_API_RE.test(url2)) return;
          var bridge = target.ElephantVideoBridge;
          if (!bridge || !bridge.reportPikpak) return;
          var text2 = String(respText || '');
          if (!text2 || text2.length > 1048576) return;
          var apiMatch = url2.match(/share\/(list|detail|file_info|verify|restore)/i);
          var api = apiMatch ? apiMatch[1] : (url2.indexOf('/drive/v1/share?') >= 0 ? 'share' : '');
          var out = {
            reqUrl: url2,
            reqBody: String(reqBody || '').slice(0, 8192),
            api: api,
            passcodeToken: '',
            headers: hdrs || {},
            files: []
          };
          try {
            var data = JSON.parse(text2);
            if (data) {
              out.passcodeToken = String(data.pass_code_token || '');
              if (api === 'file_info' && data.id) {
                out.files = [pikpakRow(data)];
              } else if (data.files && data.files.length) {
                var rows = [];
                for (var i = 0; i < data.files.length && i < 300; i++) {
                  var f = data.files[i];
                  if (!f || !f.id) continue;
                  rows.push(pikpakRow(f));
                }
                out.files = rows;
              } else if (data.data && data.data.length && data.data[0] && data.data[0].id) {
                // share/list 端点的响应是 {data:[...]} 而非 {files:[...]}
                var rows2 = [];
                for (var j = 0; j < data.data.length && j < 300; j++) {
                  var f2 = data.data[j];
                  if (!f2 || !f2.id) continue;
                  rows2.push(pikpakRow(f2));
                }
                out.files = rows2;
              }
            }
          } catch (e3) {}
          if (out.passcodeToken || out.files.length) {
            bridge.reportPikpak(JSON.stringify(out));
          }
        } catch (ignored) {}
      };
      try {
        var XHR = target.XMLHttpRequest;
        if (XHR && XHR.prototype) {
          var origOpen = XHR.prototype.open;
          var origSend = XHR.prototype.send;
          var origSetHeader = XHR.prototype.setRequestHeader;
          XHR.prototype.open = function(method, url) {
            this.__elephantUrl = url;
            return origOpen.apply(this, arguments);
          };
          if (origSetHeader) {
            XHR.prototype.setRequestHeader = function(k, v) {
              try {
                this.__elephantHdrs = this.__elephantHdrs || {};
                this.__elephantHdrs[String(k)] = String(v);
              } catch (ignored) {}
              return origSetHeader.apply(this, arguments);
            };
          }
          XHR.prototype.send = function() {
            var xhr = this;
            try { xhr.__elephantBody = arguments[0]; } catch (ignored) {}
            this.addEventListener('load', function() {
              try {
                var type = xhr.responseType;
                var text = null;
                if (!type || type === 'text') text = xhr.responseText;
                else if (type === 'json') text = JSON.stringify(xhr.response);
                if (text) {
                  reportNet(text);
                  collectPikpak(xhr.__elephantUrl, xhr.__elephantHdrs, xhr.__elephantBody, text);
                }
              } catch (ignored) {}
            });
            return origSend.apply(this, arguments);
          };
        }
      } catch (ignored) {}
      try {
        var origFetch = target.fetch;
        if (origFetch) {
          target.fetch = function() {
            var reqUrl = '';
            var reqBody = '';
            var hdrs = {};
            try {
              var input = arguments[0];
              if (typeof input === 'string') reqUrl = input;
              else if (input && input.url) reqUrl = String(input.url);
              var init = arguments[1];
              if (init) {
                if (typeof init.body === 'string') reqBody = init.body;
                var h = init.headers;
                if (h) {
                  if (typeof h.forEach === 'function') {
                    h.forEach(function(v, k) { hdrs[String(k)] = String(v); });
                  } else {
                    for (var key in h) {
                      if (Object.prototype.hasOwnProperty.call(h, key)) hdrs[String(key)] = String(h[key]);
                    }
                  }
                }
              }
            } catch (ignored) {}
            return origFetch.apply(this, arguments).then(function(resp) {
              try {
                resp.clone().text().then(function(t) {
                  reportNet(t);
                  collectPikpak(reqUrl, hdrs, reqBody, t);
                }).catch(function() {});
              } catch (ignored) {}
              return resp;
            });
          };
        }
      } catch (ignored) {}
    } catch (ignored) {}
  }
  installHook(window);
  // 周期扫描同源 iframe（跨域 iframe 访问其属性会抛异常，逐帧隔离捕获）
  try {
    setInterval(function() {
      try {
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length && i < 20; i++) {
          try { installHook(frames[i].contentWindow); } catch (ignored) {}
        }
      } catch (ignored) {}
    }, 2000);
  } catch (ignored) {}
})();
""".trimIndent()


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
