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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.translatetube.local.web.PikpakShare.PikpakEntry
import io.translatetube.local.web.PikpakShare
import io.translatetube.local.web.WebMediaUrlPolicy
import io.translatetube.local.web.SharedLinkTextParser
import io.translatetube.local.web.BilibiliDeepLinkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray

private const val TAG_WEB = "WebVideoScreen"

private fun webSourceHost(rawUrl: String): String =
    runCatching { android.net.Uri.parse(rawUrl).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.take(120)
        ?: "未知"

private fun webSourceType(rawUrl: String): String {
    val path = runCatching { android.net.Uri.parse(rawUrl).path.orEmpty() }.getOrDefault("")
    val extension = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
    return when (extension) {
        "m3u8" -> "HLS/M3U8 清单"
        "mpd" -> "DASH/MPD 清单"
        "mp4", "m4v", "webm", "flv", "mkv", "mov", "ts", "avi" -> "视频直链"
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav" -> "音频直链"
        else -> "接口或未知类型"
    }
}

private fun webSourceIssueIdentity(rawUrl: String): String =
    runCatching {
        val uri = android.net.Uri.parse(rawUrl)
        listOfNotNull(uri.host, uri.path).joinToString("|")
    }.getOrDefault(webSourceHost(rawUrl))

/** One-way per-candidate identifier lets related detection/verification errors be matched without logging signed URLs. */
private fun webSourceFingerprint(rawUrl: String): String =
    io.translatetube.local.util.AppLogStore.stableIssueKey(
        "WebSourceCandidate",
        rawUrl,
    )

private fun webSourceIssueKey(stage: String, rawUrl: String): String =
    io.translatetube.local.util.AppLogStore.stableIssueKey(
        "WebSourceDetection",
        webSourceIssueIdentity(rawUrl) + "|" + webSourceType(rawUrl) + "|" + stage,
    )

private fun webNoSourceIssueKey(pageUrl: String): String =
    io.translatetube.local.util.AppLogStore.stableIssueKey(
        "WebSourceDetection",
        webSourceIssueIdentity(pageUrl) + "|no-usable-candidate",
    )

private fun webPageLoadIssueKey(pageUrl: String): String =
    io.translatetube.local.util.AppLogStore.stableIssueKey(
        "WebPageLoad",
        webSourceIssueIdentity(pageUrl) + "|main-frame",
    )

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
    visible: Boolean = true,
    onBack: () -> Unit,
    onOpenMedia: (mediaUrl: String, title: String, referrer: String, cookie: String?, userAgent: String?) -> Unit,
) {
    val appCtx = LocalContext.current.applicationContext
    var address by remember(initialUrl) { mutableStateOf(initialUrl) }
    var currentPage by remember(initialUrl) { mutableStateOf(initialUrl) }
    val candidatesState = remember { mutableStateOf<List<WebVideoCandidate>>(emptyList()) }
    var sourcePanelHeightDp by remember(initialUrl) { mutableStateOf(72f) } // 当前面板高度（dp），可连续拖动
    var pageTitle by remember { mutableStateOf("") }
    var verificationAttempts by remember(initialUrl) { mutableStateOf(emptySet<String>()) }
    var noSourceLoggedForPage by remember(initialUrl) { mutableStateOf(false) }
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
    // ---- 候选验证：后台 HEAD/Range 请求确认媒体源是否可访问；来源由用户手动选择。 ----
    var verifiedUrls by remember(initialUrl) { mutableStateOf(emptySet<String>()) }
    var sourceHints by remember(initialUrl) { mutableStateOf<Map<String, WebCandidateAudioHint>>(emptyMap()) }
    var sourceHintAttempts by remember(initialUrl) { mutableStateOf(emptySet<String>()) }
    val sourceProbeSemaphore = remember(initialUrl) { kotlinx.coroutines.sync.Semaphore(permits = 3) }
    val verifierScope = remember(initialUrl) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    val verifyClient = remember(initialUrl) { WebCandidateVerifier.buildClient() }

    // The caller keeps this screen composed beneath the processing page. Pause its WebView
    // while hidden, but retain the same instance, detected candidates and scroll position.
    LaunchedEffect(visible) {
        screenActive.set(visible)
        val view = webViewState.value
        if (visible) {
            view?.onResume()
        } else {
            view?.onPause()
        }
    }

    DisposableEffect(Unit) {
        onDispose { verifierScope.cancel() }
    }

    // Independently inspect source hints. This labels each source but never switches the
    // user's choice; HLS probes are bounded to three concurrent requests and a small response prefix.
    LaunchedEffect(candidatesState.value, sourceHintAttempts) {
        val freshUrls = candidatesState.value
            .map { it.url }
            .distinct()
            .filter { it !in sourceHintAttempts }
        if (freshUrls.isEmpty()) return@LaunchedEffect
        val hintPageGeneration = pageGeneration.get()
        val hintPageUrl = currentPage
        sourceHintAttempts = sourceHintAttempts + freshUrls
        freshUrls.forEach { candidateUrl ->
            verifierScope.launch {
                sourceProbeSemaphore.withPermit {
                    var hintFailure: Exception? = null
                    val hint = try {
                        WebCandidateVerifier.inspectAudioHint(
                            client = verifyClient,
                            url = candidateUrl,
                            referer = hintPageUrl,
                            cookie = runCatching { CookieManager.getInstance().getCookie(candidateUrl) }.getOrNull(),
                            userAgent = webViewState.value?.settings?.userAgentString,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        hintFailure = error
                        WebCandidateVerifier.failedAudioHint(candidateUrl, error)
                    }
                    val actualHintFailure = hint.failure ?: hintFailure
                    val issueKey = webSourceIssueKey("audio-hint", candidateUrl)
                    val diagnostics = mapOf(
                        "页面主机名" to webSourceHost(hintPageUrl),
                        "媒体源主机名" to webSourceHost(candidateUrl),
                        "候选源诊断编号" to webSourceFingerprint(candidateUrl),
                        "媒体源类型" to webSourceType(candidateUrl),
                        "音轨类型识别结果" to hint.kind.name,
                        "检测说明" to hint.message,
                        "HLS 内部所选流主机名" to (hint.renditionUrl?.let { webSourceHost(it) } ?: "无"),
                        "HLS 内部所选流类型" to (hint.renditionUrl?.let { webSourceType(it) } ?: "未选择"),
                        "HLS 内部所选流诊断编号" to (hint.renditionUrl?.let { webSourceFingerprint(it) } ?: "无"),
                        "异常类型" to (actualHintFailure?.javaClass?.name ?: "检测模块未抛出异常"),
                        "异常详情" to (actualHintFailure?.message?.take(300) ?: "详见检测说明"),
                        "HTTP 状态码" to hint.message.substringAfter("HTTP ", "")
                            .take(3)
                            .takeIf { value -> value.length == 3 && value.all { it.isDigit() } }
                            .orEmpty()
                            .ifBlank { "无/未返回" },
                        "是否 HLS 检测" to hint.message.startsWith("HLS 检测失败").toString(),
                        "页面导航代数" to hintPageGeneration.toString(),
                        "检测方式" to if (WebCandidateAudioHintKind.DETECTING == hint.kind) "等待 HLS 检测" else "读取候选源音轨提示",
                    ) + hint.diagnosticContext
                    if (hint.kind == WebCandidateAudioHintKind.FAILED) {
                        // Detection failures must be visible in the "错误" log filter, not only
                        // as WARN entries. Use a dedicated tag so HLS failures can be searched
                        // directly in the in-app diagnostic history.
                        val isHlsFailure = hint.message.startsWith("HLS 检测失败")
                        io.translatetube.local.util.AppLogStore.error(
                            context = appCtx,
                            tag = if (isHlsFailure) "HlsAudioDetection" else "WebAudioTrackDetection",
                            message = if (isHlsFailure) "HLS 清单音轨检测失败" else "网页媒体源音轨类型检测失败",
                            failure = actualHintFailure,
                            diagnosticContext = diagnostics,
                            issueKey = issueKey,
                        )
                    } else {
                        io.translatetube.local.util.AppLogStore.info(
                            context = appCtx,
                            tag = "WebSourceAudioHint",
                            message = "网页媒体源音轨类型检测完成",
                            diagnosticContext = diagnostics,
                        )
                        io.translatetube.local.util.AppLogStore.resolveIssue(
                            context = appCtx,
                            issueKey = issueKey,
                            message = "网页媒体源音轨类型检测已恢复正常",
                            diagnosticContext = diagnostics,
                        )
                    }
                    mainHandler.post {
                        if (!screenActive.get() || pageGeneration.get() != hintPageGeneration) return@post
                        val oldHint = sourceHints[candidateUrl]
                        if (oldHint == null ||
                            oldHint.kind in setOf(
                                WebCandidateAudioHintKind.UNKNOWN,
                                WebCandidateAudioHintKind.DETECTING,
                                WebCandidateAudioHintKind.FAILED,
                            ) || hint.kind != WebCandidateAudioHintKind.UNKNOWN
                        ) {
                            sourceHints = sourceHints + (candidateUrl to hint)
                        }
                        val renditionUrl = hint.renditionUrl
                        if (!renditionUrl.isNullOrBlank()) {
                            val renditionHint = when (hint.kind) {
                                WebCandidateAudioHintKind.INDEPENDENT -> WebCandidateAudioHint(
                                    message = "独立音轨 · 已由主清单确认",
                                    kind = WebCandidateAudioHintKind.INDEPENDENT,
                                )
                                WebCandidateAudioHintKind.LOW_BITRATE_MUXED -> WebCandidateAudioHint(
                                    message = hint.message,
                                    kind = WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
                                )
                                else -> null
                            }
                            if (renditionHint != null) {
                                sourceHintAttempts = sourceHintAttempts + renditionUrl
                                val previous = sourceHints[renditionUrl]
                                if (previous == null ||
                                    previous.kind in setOf(
                                        WebCandidateAudioHintKind.UNKNOWN,
                                        WebCandidateAudioHintKind.DETECTING,
                                        WebCandidateAudioHintKind.FAILED,
                                    ) || renditionHint.kind != WebCandidateAudioHintKind.UNKNOWN
                                ) {
                                    sourceHints = sourceHints + (renditionUrl to renditionHint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- PikPak 分享清单（mypikpak.com/s/...）：网页 hook 结构化捕获文件树 +
    // 复用网页自带凭证主动递归枚举文件夹，视频条目直接并入候选列表点播。 ----
    var pikpakShareId by remember(initialUrl) { mutableStateOf<String?>(null) }
    var pikpakPasscodeToken by remember(initialUrl) { mutableStateOf("") }
    /** 粘贴文本里提取到的明文提取码（「密码: bubq」/?p=），detail 接口直传放行。 */
    var pikpakPassCode by remember(initialUrl) { mutableStateOf("") }
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

    // 监听候选变化，对未验证的候选后台校验，并保留 HEAD/Range 的具体证据。
    LaunchedEffect(candidatesState.value, verifiedUrls, verificationAttempts) {
        val pending = candidatesState.value
            .map { it.url }
            .distinct()
            .filter {
                WebMediaUrlPolicy.normalizeMediaUrl(it) != null &&
                    it !in verifiedUrls &&
                    it !in verificationAttempts
            }
        if (pending.isEmpty()) return@LaunchedEffect
        val verificationPageUrl = currentPage
        val verificationGeneration = pageGeneration.get()
        verificationAttempts = verificationAttempts + pending
        pending.forEach { candidateUrl ->
            verifierScope.launch {
                val result = try {
                    WebCandidateVerifier.verifyDetailed(
                        client = verifyClient,
                        url = candidateUrl,
                        referer = verificationPageUrl,
                        cookie = runCatching { CookieManager.getInstance().getCookie(candidateUrl) }.getOrNull(),
                        userAgent = webViewState.value?.settings?.userAgentString,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    WebCandidateVerificationResult(
                        verified = false,
                        method = "检测执行异常",
                        reason = "候选源校验代码异常",
                        failure = error,
                    )
                }
                val issueKey = webSourceIssueKey("candidate-verify", candidateUrl)
                val diagnostics = mapOf(
                    "页面主机名" to webSourceHost(verificationPageUrl),
                    "媒体源主机名" to webSourceHost(candidateUrl),
                    "候选源诊断编号" to webSourceFingerprint(candidateUrl),
                    "媒体源类型" to webSourceType(candidateUrl),
                    "校验方式" to result.method,
                    "校验结果" to if (result.verified) "通过" else "未通过",
                    "校验原因" to result.reason,
                    "最终 HTTP 状态码" to (result.httpStatus?.toString() ?: "未返回"),
                    "最终 Content-Type" to result.contentType.ifBlank { "未返回" },
                    "HEAD 状态码" to (result.headStatus?.toString() ?: "未返回"),
                    "HEAD Content-Type" to result.headContentType.ifBlank { "未返回" },
                    "页面导航代数" to verificationGeneration.toString(),
                    "候选总数" to candidatesState.value.size.toString(),
                    "带 Cookie 请求" to (!runCatching { CookieManager.getInstance().getCookie(candidateUrl) }.getOrNull().isNullOrBlank()).toString(),
                )
                val hasHttpError =
                    result.httpStatus?.let { it in 400..599 } == true ||
                        result.headStatus?.let { it in 400..599 } == true
                if (result.verified) {
                    io.translatetube.local.util.AppLogStore.resolveIssue(
                        context = appCtx,
                        issueKey = issueKey,
                        message = "候选媒体源重新通过可访问性校验",
                        diagnosticContext = diagnostics,
                    )
                    io.translatetube.local.util.AppLogStore.resolveIssue(
                        context = appCtx,
                        issueKey = webNoSourceIssueKey(verificationPageUrl),
                        message = "已检测到可访问的网页媒体源",
                        diagnosticContext = diagnostics,
                    )
                    io.translatetube.local.util.AppLogStore.resolveIssue(
                        context = appCtx,
                        issueKey = webSourceIssueKey("http-response", candidateUrl),
                        message = "之前出现 HTTP 错误的媒体请求已通过候选源校验",
                        diagnosticContext = diagnostics,
                    )
                } else if (result.failure != null || hasHttpError) {
                    io.translatetube.local.util.AppLogStore.warning(
                        context = appCtx,
                        tag = "WebSourceVerify",
                        message = "网页候选媒体源可访问性校验失败",
                        failure = result.failure,
                        diagnosticContext = diagnostics,
                        issueKey = issueKey,
                    )
                } else {
                    io.translatetube.local.util.AppLogStore.info(
                        context = appCtx,
                        tag = "WebSourceVerify",
                        message = "候选地址未被判定为可播放媒体源",
                        diagnosticContext = diagnostics,
                    )
                }
                if (result.verified) {
                    mainHandler.post {
                        if (!screenActive.get() || pageGeneration.get() != verificationGeneration) return@post
                        verifiedUrls = verifiedUrls + candidateUrl
                    }
                }
            }
        }
    }

    // ---- yt-dlp 兜底解析：网页直链/HLS 检测不到时，交给 yt-dlp 处理（B站/TikTok/Twitter 等） ----
    val ytScope = rememberCoroutineScope()
    var ytDlpBusy by remember(initialUrl) { mutableStateOf(false) }
    var ytDlpAutoAttempts by remember(initialUrl) { mutableStateOf(0) }
    var ytDlpHint by remember(initialUrl) { mutableStateOf<String?>(null) }
    var ytDlpDetailsExpanded by remember(currentPage) { mutableStateOf(false) }
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
            ytDlpHint = "该网页地址暂不适用于 yt-dlp 解析"
            if (screenActive.get()) {
                io.translatetube.local.util.AppLogStore.warning(
                    context = appCtx,
                    tag = "WebVideoParse",
                    message = "未启动 yt-dlp 备用解析：该站点不符合解析器支持条件",
                    diagnosticContext = mapOf(
                        "页面主机名" to webSourceHost(target),
                        "候选源数量" to candidatesState.value.size.toString(),
                        "已验证可用源数量" to verifiedUrls.size.toString(),
                        "是否为 PikPak 分享页" to (PikpakShare.parseShareId(target) != null).toString(),
                    ),
                    issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                        "WebVideoParse",
                        webSourceHost(target) + "|yt-dlp-ineligible",
                    ),
                )
            }
            return
        }
        ytDlpBusy = true
        ytDlpHint = null
        status = "正在用 yt-dlp 解析该网站视频（首次使用需解包运行时，请稍候）…"
        io.translatetube.local.util.AppLogStore.info(
            context = appCtx,
            tag = "WebVideoParse",
            message = "启动 yt-dlp 网页视频备用解析",
            diagnosticContext = mapOf(
                "页面主机名" to webSourceHost(target),
                "候选源数量" to candidatesState.value.size.toString(),
                "已验证可用源数量" to verifiedUrls.size.toString(),
                "启动方式" to if (ytDlpAutoAttempts > 0) "未发现可用直链后自动兜底" else "手动或失败后重试",
                "规则更新后是否已自动重试" to rulesUpdatedForRetry.toString(),
            ),
        )
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
                    status = "yt-dlp 解析成功：" + media.title
                    val parseIssueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                        "WebVideoParse",
                        webSourceHost(target) + "|yt-dlp",
                    )
                    val parseDiagnostics = mapOf(
                        "页面主机名" to webSourceHost(target),
                        "解析器" to "yt-dlp",
                        "解析结果" to "成功",
                        "媒体标题" to media.title.take(120),
                        "媒体地址类型" to webSourceType(media.mediaUrl),
                        "解析运行代数" to runGeneration.toString(),
                        "页面导航代数" to requestGeneration.toString(),
                    )
                    io.translatetube.local.util.AppLogStore.info(
                        context = appCtx,
                        tag = "WebVideoParse",
                        message = "yt-dlp 成功提取网页媒体地址",
                        diagnosticContext = parseDiagnostics,
                    )
                    io.translatetube.local.util.AppLogStore.resolveIssue(
                        context = appCtx,
                        issueKey = parseIssueKey,
                        message = "yt-dlp 网页视频解析已恢复成功",
                        diagnosticContext = parseDiagnostics,
                    )
                    io.translatetube.local.util.AppLogStore.resolveIssue(
                        context = appCtx,
                        issueKey = webNoSourceIssueKey(target),
                        message = "备用解析成功取得可播放媒体地址",
                        diagnosticContext = parseDiagnostics,
                    )
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
                io.translatetube.local.util.AppLogStore.error(
                    appCtx,
                    "WebVideoParse",
                    "网页视频解析失败",
                    e,
                    diagnosticContext = mapOf(
                        "页面主机名" to (runCatching { android.net.Uri.parse(target).host }.getOrNull() ?: "未知"),
                        "解析器" to "yt-dlp",
                        "页面标题" to pageTitle.take(120),
                        "解析运行代数" to runGeneration.toString(),
                        "页面导航代数" to requestGeneration.toString(),
                        "规则更新后是否已自动重试" to rulesUpdatedForRetry.toString(),
                        "当前页面是否仍活跃" to screenActive.get().toString(),
                        "候选视频数量" to candidatesState.value.size.toString(),
                    ),
                    issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                        "WebVideoParse",
                        webSourceHost(target) + "|yt-dlp",
                    ),
                )
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
                                io.translatetube.local.util.AppLogStore.error(
                                    appCtx,
                                    "YtDlp",
                                    "网页视频解析失败后自动更新规则也失败",
                                    updateError,
                                    diagnosticContext = mapOf(
                                        "页面主机名" to (runCatching { android.net.Uri.parse(target).host }.getOrNull() ?: "未知"),
                                        "解析运行代数" to runGeneration.toString(),
                                        "页面导航代数" to requestGeneration.toString(),
                                        "自动更新规则" to "已尝试",
                                    ),
                                )
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
        sourceHints = emptyMap()
        sourceHintAttempts = emptySet()
        verificationAttempts = emptySet()
        noSourceLoggedForPage = false
        // PikPak 清单跟随整页导航重置（SPA 内点文件夹不触发导航，清单保留）
        pikpakShareId = null
        pikpakPasscodeToken = ""
        pikpakPassCode = ""
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
        io.translatetube.local.util.AppLogStore.info(
            context = appCtx,
            tag = "WebSourceDetection",
            message = "开始新一轮网页视频源检测",
            diagnosticContext = mapOf(
                "页面主机名" to webSourceHost(target),
                "页面地址类型" to "网页页面",
                "检测方式" to "WebView 页面、JavaScript 探测、网络请求拦截、HEAD/Range 校验、HLS 音轨类型识别",
                "页面导航代数" to pageGeneration.get().toString(),
                "是否支持 yt-dlp 备用解析" to io.translatetube.local.yt.YtDlpRepo.isEligible(target).toString(),
                "是否为 PikPak 分享页" to (PikpakShare.parseShareId(target) != null).toString(),
            ),
        )
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
            io.translatetube.local.util.AppLogStore.warning(
                context = appCtx,
                tag = "PikPakSourceDetection",
                message = "无法读取分享清单：当前页面未识别为 PikPak 分享链接",
                diagnosticContext = mapOf(
                    "页面主机名" to webSourceHost(currentPage),
                    "页面导航代数" to pageGeneration.get().toString(),
                ),
            )
            return
        }
        pikpakShareId = shareId
        if (pikpakBusy) return
        pikpakBusy = true
        pikpakNotice = "正在读取 PikPak 分享清单…"
        val deviceUuid = PikpakShare.deviceIdentifier(appCtx)
        val passcodeToken = pikpakPasscodeToken.ifBlank { PikpakShare.capturedPasscodeToken }
        val enumerationPageUrl = currentPage
        val enumerationGeneration = pageGeneration.get()
        val hasCapturedHeaders = pikpakCapturedHeaders.isNotEmpty()
        // 增量枚举：完整枚举过的子树跳过（点具体文件夹时强制重读该子树）
        val skipParents = if (rootParentId.isBlank()) pikpakEnumeratedParents
        else pikpakEnumeratedParents - rootParentId
        pikpakScope.launch(Dispatchers.IO) {
            try {
                val result = PikpakShare.enumerateAll(
                    shareId = shareId,
                    rootParentId = rootParentId,
                    passcodeToken = passcodeToken,
                    passCode = pikpakPassCode,
                    deviceUuid = deviceUuid,
                    skipParents = skipParents,
                )
                val listIssueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                    "PikPakSourceDetection",
                    webSourceHost(enumerationPageUrl) + "|enumerate",
                )
                val diagnostics = mapOf(
                    "分享页主机名" to webSourceHost(enumerationPageUrl),
                    "目录范围" to if (rootParentId.isBlank()) "根目录" else "指定子目录",
                    "是否提供提取码令牌" to passcodeToken.isNotBlank().toString(),
                    "是否捕获到网页请求头" to hasCapturedHeaders.toString(),
                    "是否需要提取码" to result.needsPasscode.toString(),
                    "读取到的条目数" to result.entries.size.toString(),
                    "其中视频数" to result.entries.count { it.isVideo }.toString(),
                    "其中文件夹数" to result.entries.count { it.isFolder }.toString(),
                    "已访问目录数" to result.visitedParents.size.toString(),
                    "错误详情" to (result.error?.take(400) ?: "无"),
                    "页面导航代数" to enumerationGeneration.toString(),
                )
                when {
                    result.needsPasscode -> io.translatetube.local.util.AppLogStore.warning(
                        context = appCtx,
                        tag = "PikPakSourceDetection",
                        message = "读取 PikPak 清单需要有效提取码",
                        diagnosticContext = diagnostics,
                        issueKey = listIssueKey,
                    )
                    result.error != null -> io.translatetube.local.util.AppLogStore.warning(
                        context = appCtx,
                        tag = "PikPakSourceDetection",
                        message = if (result.entries.isNotEmpty()) "PikPak 清单部分读取失败" else "PikPak 清单读取失败",
                        diagnosticContext = diagnostics,
                        issueKey = listIssueKey,
                    )
                    result.entries.isEmpty() && result.visitedParents.isEmpty() -> io.translatetube.local.util.AppLogStore.warning(
                        context = appCtx,
                        tag = "PikPakSourceDetection",
                        message = "PikPak 清单请求完成，但没有返回可枚举条目",
                        diagnosticContext = diagnostics,
                        issueKey = listIssueKey,
                    )
                    else -> {
                        io.translatetube.local.util.AppLogStore.info(
                            context = appCtx,
                            tag = "PikPakSourceDetection",
                            message = "PikPak 分享清单读取完成",
                            diagnosticContext = diagnostics,
                        )
                        io.translatetube.local.util.AppLogStore.resolveIssue(
                            context = appCtx,
                            issueKey = listIssueKey,
                            message = "PikPak 分享清单读取已恢复成功",
                            diagnosticContext = diagnostics,
                        )
                    }
                }
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != enumerationGeneration) return@post
                    pikpakBusy = false
                    if (PikpakShare.capturedPasscodeToken.isNotBlank()) {
                        pikpakPasscodeToken = PikpakShare.capturedPasscodeToken
                    }
                    if (result.needsPasscode) {
                        pikpakNeedsPasscode = true
                        if (pikpakPassCode.isBlank()) {
                            pikpakNotice = "该分享需要提取码：把分享文案整段（含「密码: xxx」）粘贴到地址栏打开即可自动带码。"
                        }
                    }
                    if (result.entries.isNotEmpty()) {
                        pikpakListedParents = pikpakListedParents + result.visitedParents
                        pikpakEnumeratedParents = pikpakEnumeratedParents + result.visitedParents
                        mergePikpakEntries(result.entries)
                    }
                    pikpakNotice = when {
                        result.entries.isNotEmpty() && result.error == null ->
                            "清单已更新：共 " + pikpakEntries.count { it.isVideo } + " 个视频、" +
                                pikpakEntries.count { it.isFolder } + " 个文件夹。点下方候选或文件夹继续。"
                        result.entries.isNotEmpty() -> "部分读取成功：" + result.error
                        result.visitedParents.isNotEmpty() && result.error == null ->
                            "此前已读取过这些目录，无新增内容。"
                        else -> result.error ?: "未读取到文件，可在网页里点开文件夹后自动读取。"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                io.translatetube.local.util.AppLogStore.error(
                    context = appCtx,
                    tag = "PikPakSourceDetection",
                    message = "执行 PikPak 分享目录枚举时发生异常",
                    failure = error,
                    diagnosticContext = mapOf(
                        "分享页主机名" to webSourceHost(enumerationPageUrl),
                        "目录范围" to if (rootParentId.isBlank()) "根目录" else "指定子目录",
                        "是否提供提取码令牌" to passcodeToken.isNotBlank().toString(),
                        "是否捕获到网页请求头" to hasCapturedHeaders.toString(),
                        "页面导航代数" to enumerationGeneration.toString(),
                    ),
                    issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                        "PikPakSourceDetection",
                        webSourceHost(enumerationPageUrl) + "|enumerate",
                    ),
                )
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != enumerationGeneration) return@post
                    pikpakBusy = false
                    pikpakNotice = "读取分享清单失败：" + (error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    // PikPak share manifests use their own resolver and never enter the yt-dlp fallback.
    LaunchedEffect(currentPage, pageLoadToken) {
        delay(5000)
        if (PikpakShare.parseShareId(currentPage) != null &&
            pikpakShareId == null && pikpakEntries.isEmpty() && !pikpakBusy
        ) {
            expandPikpak("")
        }
    }

    // Give the existing WebView probe and HEAD/Range candidate verifier first chance. Any newly
    // observed candidate restarts this quiet period; yt-dlp is used automatically only after the
    // page is loaded and no verified playable media URL remains.
    LaunchedEffect(
        currentPage,
        pageLoadToken,
        pageLoading,
        candidatesState.value,
        verifiedUrls,
        ytDlpBusy,
        ytDlpAutoAttempts,
    ) {
        if (
            pageLoading ||
            ytDlpBusy ||
            ytDlpAutoAttempts != 0 ||
            PikpakShare.parseShareId(currentPage) != null
        ) return@LaunchedEffect

        delay(8000)
        val hasUsableCandidate = candidatesState.value.any { candidate ->
            candidate.url in verifiedUrls &&
                WebMediaUrlPolicy.normalizeMediaUrl(candidate.url) != null
        }
        val ytDlpEligible = io.translatetube.local.yt.YtDlpRepo.isEligible(currentPage)
        if (screenActive.get() && !hasUsableCandidate && !noSourceLoggedForPage) {
            noSourceLoggedForPage = true
            io.translatetube.local.util.AppLogStore.warning(
                context = appCtx,
                tag = "WebSourceDetection",
                message = "网页媒体源检测等待结束，仍没有确认可直接播放的候选源",
                diagnosticContext = mapOf(
                    "页面主机名" to webSourceHost(currentPage),
                    "页面标题" to pageTitle.take(120),
                    "检测等待时间" to "网页加载完成后等待约 8 秒",
                    "候选源总数" to candidatesState.value.size.toString(),
                    "已验证可访问的候选源数" to verifiedUrls.size.toString(),
                    "候选媒体类型分布" to candidatesState.value.groupingBy { webSourceType(it.url) }.eachCount().toString(),
                    "音轨类型检测结果分布" to sourceHints.values.groupingBy { it.kind.name }.eachCount().toString(),
                    "带 DRM 标记的候选数" to candidatesState.value.count { it.drmProtected }.toString(),
                    "是否支持 yt-dlp 备用解析" to ytDlpEligible.toString(),
                    "是否为 PikPak 分享页" to (PikpakShare.parseShareId(currentPage) != null).toString(),
                    "可能原因" to "可能是 DRM/blob/MSE 播放器、登录或签名限制、媒体请求被拒，或网页尚未暴露可访问的媒体地址；需结合下方 HTTP 和候选校验日志判断",
                    "页面导航代数" to pageGeneration.get().toString(),
                ),
                issueKey = webNoSourceIssueKey(currentPage),
            )
        }
        if (
            screenActive.get() &&
            WebVideoProbePolicy.shouldStartAutomaticYtDlp(
                pageLoaded = !pageLoading,
                isEligible = ytDlpEligible,
                isPikPakShare = PikpakShare.parseShareId(currentPage) != null,
                isBusy = ytDlpBusy,
                autoAttempts = ytDlpAutoAttempts,
                hasUsableCandidate = hasUsableCandidate,
            )
        ) {
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
                    if (payload.isNotBlank() && payload.trim() !in setOf("[]", "null") &&
                        payload.length > WebVideoProbePolicy.MAX_PAYLOAD_CHARS
                    ) {
                        io.translatetube.local.util.AppLogStore.warning(
                            context = appCtx,
                            tag = "WebSourceProbe",
                            message = "网页 JavaScript 媒体探测数据超过允许范围",
                            diagnosticContext = mapOf(
                                "页面主机名" to webSourceHost(currentPage),
                                "探测数据字符数" to payload.length.toString(),
                                "最大允许字符数" to WebVideoProbePolicy.MAX_PAYLOAD_CHARS.toString(),
                                "处理结果" to "为防止异常大响应占用内存，已丢弃该批探测结果",
                                "页面导航代数" to pageGeneration.get().toString(),
                            ),
                            issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                                "WebSourceProbe",
                                webSourceHost(currentPage) + "|oversized-payload",
                            ),
                        )
                    }
                    return
                }
                var reportedRows = 0
                val parsedPayload = runCatching {
                    val array = JSONArray(payload)
                    reportedRows = array.length()
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
                }
                val found = parsedPayload.getOrElse { failure ->
                    io.translatetube.local.util.AppLogStore.warning(
                        context = appCtx,
                        tag = "WebSourceProbe",
                        message = "解析网页 JavaScript 媒体探测数据失败",
                        failure = failure,
                        diagnosticContext = mapOf(
                            "页面主机名" to webSourceHost(currentPage),
                            "探测数据字符数" to payload.length.toString(),
                            "解析异常类型" to failure.javaClass.name,
                            "解析异常详情" to (failure.message ?: "无异常消息").take(300),
                            "页面导航代数" to pageGeneration.get().toString(),
                        ),
                        issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                            "WebSourceProbe",
                            webSourceHost(currentPage) + "|payload-parse",
                        ),
                    )
                    emptyList()
                }
                val bridgeGeneration = pageGeneration.get()
                mainHandler.post {
                    if (!screenActive.get() || pageGeneration.get() != bridgeGeneration) return@post
                    val previousCandidates = candidatesState.value
                    val merged = WebVideoProbePolicy.mergeCandidates(previousCandidates, found)
                    val addedCount = (merged.size - previousCandidates.size).coerceAtLeast(0)
                    candidatesState.value = merged
                    if (addedCount > 0) {
                        io.translatetube.local.util.AppLogStore.info(
                            context = appCtx,
                            tag = "WebSourceProbe",
                            message = "网页 JavaScript 探测发现媒体候选源",
                            diagnosticContext = mapOf(
                                "页面主机名" to webSourceHost(currentPage),
                                "页面标题" to pageTitle.take(120),
                                "网页报告的资源行数" to reportedRows.toString(),
                                "成功解析为可用候选的行数" to found.size.toString(),
                                "新增候选数量" to addedCount.toString(),
                                "候选源总数" to merged.size.toString(),
                                "媒体资源类型分布" to found.groupingBy { webSourceType(it.url) }.eachCount().toString(),
                                "带 DRM 标记的候选数" to found.count { it.drmProtected }.toString(),
                                "来源通道" to "JavaScript DOM / 页面网络 hook",
                                "页面导航代数" to bridgeGeneration.toString(),
                            ),
                        )
                    } else if (reportedRows > 0 && found.isEmpty()) {
                        io.translatetube.local.util.AppLogStore.info(
                            context = appCtx,
                            tag = "WebSourceProbe",
                            message = "网页探测有返回内容，但未筛选出符合条件的媒体地址",
                            diagnosticContext = mapOf(
                                "页面主机名" to webSourceHost(currentPage),
                                "网页报告的资源行数" to reportedRows.toString(),
                                "筛选后候选数量" to found.size.toString(),
                                "筛选规则" to "只保留允许的 HTTP/HTTPS 媒体候选地址；完整 URL 未写入日志",
                                "页面导航代数" to bridgeGeneration.toString(),
                            ),
                        )
                    }
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
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (visible) 1f else 0f },
        topBar = {
            TopAppBar(
                title = { Text("网页视频", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        enabled = webViewState.value != null,
                        onClick = {
                            val view = webViewState.value
                            if (view == null) {
                                status = "网页尚未就绪，请稍后重试。"
                            } else {
                                status = "正在重新检测视频…"
                                pageLoading = true
                                candidatesState.value = emptyList()
                                verifiedUrls = emptySet()
                                sourceHints = emptyMap()
                                sourceHintAttempts = emptySet()
                                runCatching { view.stopLoading() }
                                view.reload()
                            }
                        },
                    ) {
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
                    val rawAddress = address.trim()
                    val inputUrl = SharedLinkTextParser.extractFirstHttpUrl(rawAddress) ?: rawAddress
                    // 粘贴文本里带提取码（「密码: bubq」/「?p=」等）时先记下，
                    // 进入 PikPak 分享页后自动带入，无需网页手动输码。
                    pikpakPassCode = PikpakShare.extractPassCode(rawAddress).orEmpty()
                    val normalized = WebMediaUrlPolicy.normalizePageUrl(inputUrl)
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

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
            val viewportMaxHeight = maxHeight
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
                                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                    io.translatetube.local.util.AppLogStore.warning(
                                        context = appCtx,
                                        tag = "WebPlayerConsole",
                                        message = "网页播放器 JavaScript 报错",
                                        diagnosticContext = mapOf(
                                            "页面主机名" to webSourceHost(currentPage),
                                            "错误内容" to message.message().take(300),
                                            "脚本主机名" to webSourceHost(message.sourceId()),
                                            "脚本行号" to message.lineNumber().toString(),
                                            "控制台级别" to message.messageLevel().name,
                                            "页面导航代数" to pageGeneration.get().toString(),
                                        ),
                                        issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                                            "WebPlayerConsole",
                                            webSourceHost(currentPage) + "|javascript-error",
                                        ),
                                    )
                                }
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            /**
                             * Bilibili share links can redirect to bilibili://video/... rather than
                             * an HTTP page. Convert recognized Bilibili video deep links and keep
                             * navigation inside this WebView so video-source detection can run.
                             */
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                if (!request.isForMainFrame) return false
                                val originalUrl = request.url.toString()
                                val convertedUrl = BilibiliDeepLinkPolicy.toWebUrl(originalUrl)
                                    ?: return false
                                val safeUrl = WebMediaUrlPolicy.normalizePageUrl(convertedUrl)
                                    ?: return false
                                val originalScheme = request.url.scheme.orEmpty()

                                mainHandler.post {
                                    if (!screenActive.get()) return@post
                                    address = safeUrl
                                    status = "已识别 B 站分享链接，正在打开视频网页…"
                                    io.translatetube.local.util.AppLogStore.info(
                                        context = appCtx,
                                        tag = "WebPageLoad",
                                        message = "已将 B 站客户端深链转换为 HTTPS 视频页",
                                        diagnosticContext = mapOf(
                                            "原始跳转协议" to originalScheme,
                                            "转换后页面主机名" to webSourceHost(safeUrl),
                                            "页面导航代数" to pageGeneration.get().toString(),
                                            "处理方式" to "在应用内继续加载并检测视频源",
                                        ),
                                    )
                                    view.loadUrl(safeUrl)
                                }
                                return true
                            }

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
                                io.translatetube.local.util.AppLogStore.info(
                                    context = appCtx,
                                    tag = "WebSourceDetection",
                                    message = "网页加载完成，开始执行媒体源探测",
                                    diagnosticContext = mapOf(
                                        "页面主机名" to webSourceHost(url),
                                        "页面标题" to pageTitle.take(120),
                                        "页面导航代数" to pageGeneration.get().toString(),
                                        "探测前候选源数量" to candidatesState.value.size.toString(),
                                        "下一步" to "执行 JavaScript 媒体探测并持续监听网络请求",
                                    ),
                                )
                                io.translatetube.local.util.AppLogStore.resolveIssue(
                                    context = appCtx,
                                    issueKey = webPageLoadIssueKey(url),
                                    message = "网页主文档已成功加载",
                                    diagnosticContext = mapOf(
                                        "页面主机名" to webSourceHost(url),
                                        "页面导航代数" to pageGeneration.get().toString(),
                                    ),
                                )
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
                                                                                val previousCandidates = candidatesState.value
                                        val mergedCandidates = WebVideoProbePolicy.mergeCandidates(
                                            previousCandidates,
                                            listOf(candidate),
                                        )
                                        val addedCount = (mergedCandidates.size - previousCandidates.size).coerceAtLeast(0)
                                        candidatesState.value = mergedCandidates
                                        // The per-candidate type classification below carries individual fingerprints.
                                        // Only log the first network-intercept discovery here to avoid flooding a bounded log
                                        // with a separate identical event for every segment/resource on a complex page.
                                        if (addedCount > 0 && previousCandidates.isEmpty()) {
                                            io.translatetube.local.util.AppLogStore.info(
                                                context = appCtx,
                                                tag = "WebSourceDetection",
                                                message = "从网页网络请求中捕获到媒体候选源",
                                                diagnosticContext = mapOf(
                                                    "页面主机名" to webSourceHost(currentPage),
                                                    "媒体源主机名" to webSourceHost(resourceUrl),
                                                    "候选源诊断编号" to webSourceFingerprint(resourceUrl),
                                                    "媒体源类型" to webSourceType(resourceUrl),
                                                    "请求方法" to request.method,
                                                    "新增候选数量" to addedCount.toString(),
                                                    "候选源总数" to mergedCandidates.size.toString(),
                                                    "来源通道" to "WebView shouldInterceptRequest",
                                                    "页面导航代数" to requestGeneration.toString(),
                                                ),
                                            )
                                        }
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
                                        val failedUrl = request.url.toString()
                                        if (screenActive.get() && failedUrl == currentPage) {
                                            pageLoading = false
                                            status = "网页加载失败：" + error.description
                                            io.translatetube.local.util.AppLogStore.warning(
                                                context = appCtx,
                                                tag = "WebPageLoad",
                                                message = "WebView 网页主文档加载失败",
                                                diagnosticContext = mapOf(
                                                    "页面主机名" to webSourceHost(failedUrl),
                                                    "错误代码" to error.errorCode.toString(),
                                                    "错误详情" to error.description.toString().take(300),
                                                    "是否主文档请求" to request.isForMainFrame.toString(),
                                                    "HTTP 方法" to request.method,
                                                    "页面导航代数" to pageGeneration.get().toString(),
                                                    "可能影响" to "网页无法加载时，JavaScript 探测与网络媒体源监听可能无法开始",
                                                ),
                                                issueKey = webPageLoadIssueKey(failedUrl),
                                            )
                                        }
                                    }
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse,
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                val failedUrl = request.url.toString()
                                val isMediaRequest = WebVideoProbePolicy.acceptsMediaUrl(failedUrl) &&
                                    WebMediaUrlPolicy.looksLikeMediaRequest(failedUrl)
                                if (!request.isForMainFrame && !isMediaRequest) return
                                val issueKey = if (request.isForMainFrame) {
                                    webPageLoadIssueKey(failedUrl)
                                } else {
                                    webSourceIssueKey("http-response", failedUrl)
                                }
                                io.translatetube.local.util.AppLogStore.warning(
                                    context = appCtx,
                                    tag = "WebSourceHttp",
                                    message = if (request.isForMainFrame) "网页主文档返回 HTTP 错误" else "媒体候选请求返回 HTTP 错误",
                                    diagnosticContext = mapOf(
                                        "资源主机名" to webSourceHost(failedUrl),
                                        "候选源诊断编号" to if (request.isForMainFrame) "网页主文档" else webSourceFingerprint(failedUrl),
                                        "资源类型" to if (request.isForMainFrame) "网页文档" else webSourceType(failedUrl),
                                        "HTTP 状态码" to errorResponse.statusCode.toString(),
                                        "HTTP 原因" to errorResponse.reasonPhrase.take(200),
                                        "HTTP 方法" to request.method,
                                        "是否主文档请求" to request.isForMainFrame.toString(),
                                        "响应 Content-Type" to (errorResponse.mimeType ?: "未提供"),
                                        "页面导航代数" to pageGeneration.get().toString(),
                                        "可能原因" to when (errorResponse.statusCode) {
                                            401 -> "需要登录或身份验证"
                                            403 -> "服务器拒绝访问，可能涉及防盗链、权限或签名限制"
                                            404 -> "地址不存在或媒体签名已过期"
                                            429 -> "请求过于频繁或触发站点限流"
                                            else -> "需结合响应状态、来源类型和候选校验日志进一步判断"
                                        },
                                    ),
                                    issueKey = issueKey,
                                )
                            }

                        }
                        val initial = WebMediaUrlPolicy.normalizePageUrl(initialUrl)
                        if (initial != null) {
                            loadUrl(initial)
                        } else {
                            status = "网页地址无效，请返回后检查链接。"
                            io.translatetube.local.util.AppLogStore.warning(
                                context = appCtx,
                                tag = "WebPageLoad",
                                message = "网页视频源检测未启动：初始网页地址无效",
                                diagnosticContext = mapOf(
                                    "输入是否为空" to initialUrl.isBlank().toString(),
                                    "地址是否符合 HTTP/HTTPS 规则" to "否",
                                    "可能影响" to "未开始 WebView、媒体候选捕获与 yt-dlp 解析",
                                ),
                                issueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                                    "WebPageLoad",
                                    "invalid-initial-url",
                                ),
                            )
                        }
                    }
                },
                update = { },
            )


            val minPanelHeight = 80.dp.coerceAtMost(maxHeight)
            val maxPanelHeight = (maxHeight * 0.92f)
                .coerceAtLeast(minPanelHeight)
                .coerceAtMost(maxHeight)
            val targetPanelHeight = sourcePanelHeightDp.dp.coerceIn(minPanelHeight, maxPanelHeight)
            val panelContentThreshold = minPanelHeight + 84.dp.coerceAtMost(maxPanelHeight - minPanelHeight)
            val panelContentVisible = targetPanelHeight >= panelContentThreshold
            val density = LocalDensity.current
            val panelDragModifier = Modifier.pointerInput(minPanelHeight, maxPanelHeight, density) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        val deltaDp = with(density) { dragAmount.toDp().value }
                        sourcePanelHeightDp = (sourcePanelHeightDp - deltaDp)
                            .coerceIn(minPanelHeight.value, maxPanelHeight.value)
                    },
                )
            }
            val panelClickModifier = Modifier.clickable {
                // 点击快速展开/收起；上下拖动则可以精确停在任意高度。
                sourcePanelHeightDp = if (targetPanelHeight > minPanelHeight + 8.dp) {
                    minPanelHeight.value
                } else {
                    (minPanelHeight + (maxPanelHeight - minPanelHeight) * 0.58f).value
                }
            }
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(targetPanelHeight),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 14.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 仅顶部把手支持连续拖动，避免标题和视频源列表误触发面板移动。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .then(panelDragModifier)
                            .then(panelClickModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(panelClickModifier)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "检测到的视频源",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text(
                                when {
                                    candidatesState.value.isNotEmpty() ->
                                        "${candidatesState.value.size} 个候选源 · ${verifiedUrls.size} 个已验证"
                                    pageLoading || ytDlpBusy -> status
                                    else -> status.ifBlank { "等待检测视频源" }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = if (targetPanelHeight > minPanelHeight + 8.dp) {
                                Icons.Filled.KeyboardArrowDown
                            } else {
                                Icons.Filled.KeyboardArrowUp
                            },
                            contentDescription = if (targetPanelHeight > minPanelHeight + 8.dp) {
                                "收起视频源列表"
                            } else {
                                "展开视频源列表"
                            },
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    if (panelContentVisible) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                if (candidatesState.value.isEmpty() && !pageLoading && !ytDlpBusy) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AnimatedVisibility(
                    visible = pageLoading || ytDlpBusy,
                    enter = fadeIn(animationSpec = tween(150)) +
                        expandVertically(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                        ),
                    exit = fadeOut(animationSpec = tween(110)) +
                        shrinkVertically(
                            animationSpec = tween(150, easing = FastOutSlowInEasing),
                        ),
                ) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (ytDlpDetailsExpanded) ytDlpHint.orEmpty() else "yt-dlp 解析失败 · 点击查看原因",
                            modifier = Modifier
                                .weight(1f)
                                .clickable { ytDlpDetailsExpanded = !ytDlpDetailsExpanded },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = if (ytDlpDetailsExpanded) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (io.translatetube.local.yt.YtDlpRepo.isEligible(currentPage)) {
                            var updatingRules by remember(currentPage) { mutableStateOf(false) }
                            TextButton(
                                modifier = Modifier.heightIn(min = 32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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
                                            io.translatetube.local.util.AppLogStore.error(
                                                appCtx,
                                                "YtDlp",
                                                "手动更新网页视频解析规则失败",
                                                e,
                                                diagnosticContext = mapOf(
                                                    "页面主机名" to (runCatching { android.net.Uri.parse(currentPage).host }.getOrNull() ?: "未知"),
                                                    "操作" to "用户从网页视频页手动更新规则",
                                                    "当前页面是否仍活跃" to screenActive.get().toString(),
                                                ),
                                            )
                                            mainHandler.post {
                                                updatingRules = false
                                                ytDlpHint = "规则更新失败：${e.message ?: "网络错误"}"
                                                status = "规则更新失败，可稍后重试。"
                                            }
                                        }
                                    }
                                },
                            ) { Text(if (updatingRules) "更新中…" else "更新规则", maxLines = 1) }
                        }
                    }
                }
                if (
                    !pageLoading &&
                    !ytDlpBusy &&
                    ytDlpAutoAttempts == 0 &&
                    candidatesState.value.none { candidate ->
                        candidate.url in verifiedUrls &&
                            WebMediaUrlPolicy.normalizeMediaUrl(candidate.url) != null
                    } &&
                    PikpakShare.parseShareId(currentPage) == null &&
                    WebMediaUrlPolicy.normalizePageUrl(currentPage) != null
                ) {
                    Text(
                        "候选源验证中，必要时将尝试备用解析。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(durationMillis = 150),
                                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                            fadeOutSpec = tween(durationMillis = 110),
                                        )
                                        .fillMaxWidth(),
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
                    // 面板标题已说明视频源，避免重复占用一行。
                    // 验证通过的候选排最前（后台已确认是可播放的流），其次是 m3u8/mpd 清单
                    val displayCandidates = remember(candidatesState.value, verifiedUrls, sourceHints) {
                        candidatesState.value
                            .mapIndexed { index, c -> Pair(c, index) }
                            .sortedWith(
                                object : Comparator<Pair<WebVideoCandidate, Int>> {
                                    override fun compare(
                                        a: Pair<WebVideoCandidate, Int>,
                                        b: Pair<WebVideoCandidate, Int>,
                                    ): Int {
                                        fun extractionRank(url: String): Int = when (sourceHints[url]?.kind) {
                                            WebCandidateAudioHintKind.INDEPENDENT -> 2
                                            WebCandidateAudioHintKind.LOW_BITRATE_MUXED -> 1
                                            else -> 0
                                        }
                                        val aExtractionRank = extractionRank(a.first.url)
                                        val bExtractionRank = extractionRank(b.first.url)
                                        if (aExtractionRank != bExtractionRank) return bExtractionRank - aExtractionRank

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
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(displayCandidates, key = { it.url }) { candidate ->
                            val sourceHint = sourceHints[candidate.url]
                                ?: WebCandidateVerifier.initialAudioHint(candidate.url)
                            val hintColor = when (sourceHint.kind) {
                                WebCandidateAudioHintKind.INDEPENDENT -> MaterialTheme.colorScheme.primary
                                WebCandidateAudioHintKind.LOW_BITRATE_MUXED -> MaterialTheme.colorScheme.tertiary
                                WebCandidateAudioHintKind.DETECTING -> MaterialTheme.colorScheme.primary
                                WebCandidateAudioHintKind.FAILED -> MaterialTheme.colorScheme.error
                                WebCandidateAudioHintKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val reason = when {
                                candidate.drmProtected -> "检测到 EME/DRM 标记，不能提取或绕过受保护的媒体音轨。"
                                else -> WebMediaUrlPolicy.unsupportedReason(candidate.url)
                            }
                            Card(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = tween(durationMillis = 160),
                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        fadeOutSpec = tween(durationMillis = 120),
                                    )
                                    .fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                candidate.title.ifBlank { pageTitle.ifBlank { "网页视频" } },
                                                modifier = Modifier.weight(1f),
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
                                        Text(
                                            sourceHint.message,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = hintColor,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            candidate.url,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (sourceHint.kind == WebCandidateAudioHintKind.FAILED) {
                                            Text(
                                                "重新检测",
                                                modifier = Modifier
                                                    .clickable {
                                                        sourceHintAttempts = sourceHintAttempts - candidate.url
                                                        sourceHints = sourceHints +
                                                            (candidate.url to WebCandidateVerifier.initialAudioHint(candidate.url))
                                                    }
                                                    .padding(vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (reason != null) {
                                            Text(
                                                reason,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Button(
                                        modifier = Modifier
                                            .widthIn(min = 100.dp)
                                            .defaultMinSize(minHeight = 36.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        enabled = reason == null,
                                        onClick = {
                                            val mediaUrl = WebMediaUrlPolicy.normalizeMediaUrl(candidate.url)
                                            if (mediaUrl == null) {
                                                val reasonText = WebMediaUrlPolicy.unsupportedReason(candidate.url)
                                                    ?: "无法读取该媒体地址。"
                                                status = reasonText
                                                io.translatetube.local.util.AppLogStore.warning(
                                                    context = appCtx,
                                                    tag = "WebSourceSelection",
                                                    message = "用户选择的网页候选源无法转换为可播放媒体地址",
                                                    diagnosticContext = mapOf(
                                                        "页面主机名" to webSourceHost(currentPage),
                                                        "媒体源主机名" to webSourceHost(candidate.url),
                                                        "候选源诊断编号" to webSourceFingerprint(candidate.url),
                                                        "媒体源类型" to webSourceType(candidate.url),
                                                        "失败原因" to reasonText,
                                                        "是否带 DRM 标记" to candidate.drmProtected.toString(),
                                                        "候选源是否通过可访问性校验" to (candidate.url in verifiedUrls).toString(),
                                                    ),
                                                    issueKey = webSourceIssueKey("selection", candidate.url),
                                                )
                                            } else {
                                                val cookie = runCatching { CookieManager.getInstance().getCookie(mediaUrl) }.getOrNull()
                                                val hint = sourceHints[candidate.url]
                                                    ?: WebCandidateVerifier.initialAudioHint(candidate.url)
                                                io.translatetube.local.util.AppLogStore.info(
                                                    context = appCtx,
                                                    tag = "WebSourceSelection",
                                                    message = "用户选择网页候选媒体源进入翻译播放",
                                                    diagnosticContext = mapOf(
                                                        "页面主机名" to webSourceHost(currentPage),
                                                        "媒体源主机名" to webSourceHost(mediaUrl),
                                                        "候选源诊断编号" to webSourceFingerprint(mediaUrl),
                                                        "媒体源类型" to webSourceType(mediaUrl),
                                                        "媒体源标题" to candidate.title.take(120),
                                                        "音轨类型识别结果" to hint.kind.name,
                                                        "音轨类型说明" to hint.message,
                                                        "是否通过 HEAD/Range 校验" to (candidate.url in verifiedUrls).toString(),
                                                        "是否带 DRM 标记" to candidate.drmProtected.toString(),
                                                        "是否存在站点 Cookie" to (!cookie.isNullOrBlank()).toString(),
                                                        "候选源总数" to candidatesState.value.size.toString(),
                                                        "页面导航代数" to pageGeneration.get().toString(),
                                                    ),
                                                )
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
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("翻译播放", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
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
