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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlin.math.abs

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

/** The compact card shows only the detected audio-track type. */
private fun WebCandidateAudioHint.compactSummary(): String = when (kind) {
    WebCandidateAudioHintKind.DETECTING -> "检测中"
    WebCandidateAudioHintKind.INDEPENDENT -> "独立音轨"
    WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
    WebCandidateAudioHintKind.MUXED_DIRECT -> "音画混合"
    WebCandidateAudioHintKind.NO_AUDIO -> "无音轨"
    WebCandidateAudioHintKind.FAILED -> "检测失败"
    WebCandidateAudioHintKind.UNKNOWN -> "待确认"
}

/** Keep resolution visible; codecs, bitrates and all remaining media fields stay under More. */
private fun WebCandidateAudioHint.resolutionSummary(): String {
    val info = mediaInfo ?: return "待识别"
    if (info.hasVideo == false) return "无视频"

    val minHeight = info.videoHeightMin
    val maxHeight = info.videoHeightMax
    if (minHeight != null && maxHeight != null && minHeight != maxHeight) {
        return "${minHeight}p–${maxHeight}p"
    }
    return WebMediaFormat.resolutionLabel(info.videoWidth, info.videoHeight) ?: "待识别"
}

/** Detailed media fields for the opt-in expansion on each candidate card. */
private fun WebCandidateAudioHint.detailRows(): List<Pair<String, String>> {
    val info = mediaInfo
    if (info == null) {
        return buildList {
            if (message.isNotBlank()) add("检测说明" to message)
            diagnosticContext.entries
                .filter { it.key != "检测步骤" && it.value.isNotBlank() }
                .take(12)
                .forEach { add(it.key to it.value) }
        }
    }

    return buildList {
        info.container?.let { add("容器格式" to it) }
        info.hasVideo?.let { add("视频轨" to if (it) "有" else "无") }
        info.hasAudio?.let { add("音轨" to if (it) "有" else "无") }
        if (info.audioTrackCount > 0) add("音轨数量" to info.audioTrackCount.toString())
        info.durationSeconds?.takeIf { it > 0.0 }?.let {
            add("时长" to WebMediaFormat.duration(it))
        }
        info.videoCodec?.let { add("视频编码" to it) }
        WebMediaFormat.resolutionLabel(info.videoWidth, info.videoHeight)?.let {
            add("视频分辨率" to it)
        }
        if (info.videoHeightMin != null && info.videoHeightMax != null &&
            info.videoHeightMin != info.videoHeightMax
        ) {
            add("清晰度范围" to "${info.videoHeightMin}p–${info.videoHeightMax}p")
        }
        if (info.videoVariantCount > 1) add("画质档位数量" to info.videoVariantCount.toString())
        val videoRange = info.videoBitrateMinBps?.let { min ->
            info.videoBitrateMaxBps?.takeIf { it != min }?.let { max ->
                "${WebMediaFormat.bitrate(min)}–${WebMediaFormat.bitrate(max)}"
            }
        }
        val videoBitrate = videoRange ?: info.videoBitrateBps?.let { WebMediaFormat.bitrate(it) }
        videoBitrate?.let { add("视频码率" to (info.videoBitrateBasis.prefix + it)) }
        if (videoBitrate != null) add("视频码率依据" to info.videoBitrateBasis.diagnosticName)
        info.audioCodec?.let { add("音频编码" to it) }
        info.audioChannels?.let { add("音频声道" to WebMediaFormat.channels(it)) }
        info.audioSampleRate?.let { add("音频采样率" to "${it} Hz") }
        info.audioBitrateBps?.let {
            add("音频码率" to (info.audioBitrateBasis.prefix + WebMediaFormat.bitrate(it)))
            add("音频码率依据" to info.audioBitrateBasis.diagnosticName)
        }
        info.audioLanguage?.takeIf { it.isNotBlank() }?.let { add("音频语言" to it) }
    }
}

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
    var sourcePanelHeightDp by remember(initialUrl) { mutableStateOf(8f) } // 默认完全收起，只在屏幕底边显示把手
    var pageTitle by remember { mutableStateOf("") }
    var verificationAttempts by remember(initialUrl) { mutableStateOf(emptySet<String>()) }
    var noSourceLoggedForPage by remember(initialUrl) { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在打开网页并检测 HTML5 视频…") }
    var pageLoading by remember(initialUrl) { mutableStateOf(WebMediaUrlPolicy.normalizePageUrl(initialUrl) != null) }
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    // Preserve the device WebView's real Chromium version; only remove the WebView marker for PikPak.
    var defaultWebViewUserAgent by remember(initialUrl) { mutableStateOf<String?>(null) }
    // One recovery reload per page URL prevents blank-page retries from looping indefinitely.
    var pikpakBlankRetryUrl by remember(initialUrl) { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val screenActive = remember(initialUrl) { java.util.concurrent.atomic.AtomicBoolean(true) }
    // A single top-level navigation can emit several onPageStarted callbacks while a short link
    // redirects through multiple Bilibili URLs. Keep one detection generation for the whole chain.
    val navigationInProgress = remember(initialUrl) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val lastCompletedNavigationUrl = remember(initialUrl) {
        java.util.concurrent.atomic.AtomicReference<String?>(null)
    }
    val lastCompletedNavigationGeneration = remember(initialUrl) {
        java.util.concurrent.atomic.AtomicLong(-1L)
    }
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
        // WebView methods (including settings) may only be called on the main thread, and the
        // probes below run on Dispatchers.IO. Read the UA here, where we are on the main thread.
        val hintUserAgent = webViewState.value?.settings?.userAgentString
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
                            userAgent = hintUserAgent,
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
                                    message = "独立音轨 · 已由主清单确认" +
                                        (hint.mediaInfo?.audioSummary()?.let { " · $it" } ?: ""),
                                    kind = WebCandidateAudioHintKind.INDEPENDENT,
                                    mediaInfo = hint.mediaInfo,
                                )
                                WebCandidateAudioHintKind.LOW_BITRATE_MUXED -> WebCandidateAudioHint(
                                    message = hint.message,
                                    kind = WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
                                    mediaInfo = hint.mediaInfo,
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
        // Main-thread read: the verification below runs on Dispatchers.IO, where touching the
        // WebView throws "A WebView method was called on thread ...".
        val verificationUserAgent = webViewState.value?.settings?.userAgentString
        verificationAttempts = verificationAttempts + pending
        pending.forEach { candidateUrl ->
            verifierScope.launch {
                val result = try {
                    WebCandidateVerifier.verifyDetailed(
                        client = verifyClient,
                        url = candidateUrl,
                        referer = verificationPageUrl,
                        cookie = runCatching { CookieManager.getInstance().getCookie(candidateUrl) }.getOrNull(),
                        userAgent = verificationUserAgent,
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
    var ytDlpUpdatingRules by remember(currentPage) { mutableStateOf(false) }
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

    /**
     * PikPak 需要手机版浏览器识别，但不能伪造固定 Chrome 版本：服务器可能据此下发
     * 当前 WebView 内核无法执行的新脚本。保留系统 UA 的真实内核版本，只移除 Android
     * WebView 标记「; wv)」和仅供 WebView 使用的「Version/4.0」。离开 PikPak 后恢复原 UA。
     */
    fun applyPikpakUserAgent(target: String) {
        val webView = webViewState.value ?: return
        val host = runCatching {
            android.net.Uri.parse(target).host?.lowercase(java.util.Locale.ROOT).orEmpty()
        }.getOrDefault("")
        val isPikpak = host == "mypikpak.com" || host.endsWith(".mypikpak.com") ||
            host == "pikpak.com" || host.endsWith(".pikpak.com")
        val nativeAgent = defaultWebViewUserAgent ?: webView.settings.userAgentString
        runCatching {
            webView.settings.userAgentString = if (isPikpak) {
                nativeAgent
                    .replace("; wv)", ")", ignoreCase = true)
                    .replace("Version/4.0 ", "", ignoreCase = true)
            } else {
                nativeAgent
            }
        }.onFailure { Log.w(TAG_WEB, "Unable to set page-specific WebView user agent", it) }
    }

    fun resetForNavigation(target: String) {
        applyPikpakUserAgent(target)
        navigationInProgress.set(true)
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
                    // HLS 播放链优先：分段清单是全量内容（播放完整、音轨提取走
                    // HlsAudioDownloader 分段下载同样完整）；下载直链在游客态
                    // 常被 CDN 流式截断成预览段——「只能播一小部分」的根因。
                    url = it.mediasUrl.ifBlank { it.downloadUrl },
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
                            pikpakNotice = "请在上方 PikPak 官方网页手动输入提取码并完成验证；验证成功后会继续读取清单。"
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

    // PikPak 分享页不在加载后立即调用私有接口枚举。
    // 需要提取码时，先由用户在上方官方网页手动输入并验证；网页 hook 捕获到验证令牌后
    // 再自动读取清单。若页面没有捕获到清单，用户也可在面板中手动点击读取/刷新。

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
                    val previousUrls = previousCandidates.map { it.url }.toHashSet()
                    val merged = WebVideoProbePolicy.mergeCandidates(previousCandidates, found)
                    val candidatesChanged = merged != previousCandidates
                    val addedCount = found.count { it.url !in previousUrls }
                    // The DOM probe reports on a timer. Do not publish an identical candidate list:
                    // doing so reruns Compose effects and can look like source detection is looping.
                    if (candidatesChanged) candidatesState.value = merged
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
                    if (addedCount > 0) {
                        status = "发现 ${merged.size} 个媒体地址；选择可直接访问且未受 DRM 保护的源即可继续。"
                    } else if (merged.isEmpty() && status == "网页已加载，正在检测视频源…") {
                        // Only show the empty-result state once; later polling must not override a
                        // more informative status produced by parsing or candidate validation.
                        status = "网页已加载，但暂未检测到媒体地址。DRM、blob/MSE 或封闭式播放器可能无法提取。"
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
                    val hasNewVerifiedPasscodeToken =
                        data.passcodeToken.isNotBlank() && data.passcodeToken != pikpakPasscodeToken
                    if (data.passcodeToken.isNotBlank()) {
                        // 只有网页返回有效 pass_code_token 后才认为官方验证流程已经继续。
                        pikpakPasscodeToken = data.passcodeToken
                        pikpakNeedsPasscode = false
                        pikpakPassCode = ""
                    }
                    if (data.parentId.isNotBlank()) {
                        pikpakListedParents = pikpakListedParents + data.parentId
                    }
                    if (data.entries.isNotEmpty()) {
                        pikpakNeedsPasscode = false
                        mergePikpakEntries(data.entries)
                        val videoCount = pikpakEntries.count { it.isVideo }
                        val folderCount = pikpakEntries.count { it.isFolder }
                        pikpakNotice = "已从网页捕获清单：$videoCount 个视频、$folderCount 个文件夹。"
                    }
                    if (hasNewVerifiedPasscodeToken) {
                        // 官方页面验证成功后，复用它刚返回的 token 继续读取分享清单。
                        mainHandler.postDelayed({
                            if (screenActive.get() &&
                                pageGeneration.get() == bridgeGeneration &&
                                !pikpakBusy
                            ) {
                                expandPikpak("")
                            }
                        }, 250)
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
                                // Use the same single-generation reset as address navigation.
                                // onPageStarted recognizes this prepared reload and will not clear
                                // candidates or start another detection round for the same action.
                                resetForNavigation(currentPage)
                                pageLoadToken++
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
                    // 受密码保护的分享保持官方网页原始验证流程：
                    // 用户在网页内手动输入提取码，不从粘贴文本自动填入私有接口。
                    pikpakPassCode = ""
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
            // 固定网页窗口高度：默认视频源面板的顶部就是网页窗口底边。
            // 面板拖动或收起时只改变面板，不重新测量/缩放网页窗口。
            val panelPeekHeight = 112.dp.coerceAtMost(maxHeight)
            val fixedWebHeight = (maxHeight - panelPeekHeight).coerceAtLeast(0.dp)
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(fixedWebHeight)
                    .align(Alignment.TopCenter),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewState.value = this
                        if (defaultWebViewUserAgent == null) {
                            defaultWebViewUserAgent = settings.userAgentString
                        }
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
                        // 渲染保险：SPA 分享页可能引用 http 混合资源/依赖第三方 cookie
                        // （默认 WebView 会静默拦掉，造成页面空白半成品）。
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        runCatching {
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }
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
                                            "页面标题" to pageTitle.take(120).ifBlank { "无标题" },
                                            "是否识别为 PikPak 分享页" to (PikpakShare.parseShareId(currentPage) != null).toString(),
                                            "当前页面加载状态" to if (pageLoading) "仍在加载" else "已完成加载回调",
                                            "WebView 内核版本" to if (android.os.Build.VERSION.SDK_INT >= 26) {
                                                runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull().orEmpty().ifBlank { "系统未返回" }
                                            } else {
                                                "当前 Android API 不提供此接口"
                                            },
                                            "当前 UA 是否带 wv 标记" to (webViewState.value?.settings?.userAgentString?.contains("; wv)", ignoreCase = true) == true).toString(),
                                            "脚本来源与页面是否同主机" to (webSourceHost(message.sourceId()) == webSourceHost(currentPage)).toString(),
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
                                val duplicateCompletedBilibiliPage =
                                    lastCompletedNavigationGeneration.get() == pageGeneration.get() &&
                                        WebVideoProbePolicy.isDuplicateCompletedNavigation(
                                            targetUrl = url,
                                            currentPageUrl = currentPage,
                                            lastCompletedUrl = lastCompletedNavigationUrl.get(),
                                        )
                                val sameBilibiliVideoAlreadyDetected =
                                    WebVideoProbePolicy.isSameBilibiliVideoPage(url, currentPage)
                                when {
                                    duplicateCompletedBilibiliPage || sameBilibiliVideoAlreadyDetected -> {
                                        // Bilibili may emit a repeated start callback after its video
                                        // sources are already visible. Do not clear candidates or restart detection.
                                        applyPikpakUserAgent(url)
                                        currentPage = url
                                        address = url
                                        Log.d(TAG_WEB, "Ignoring repeated navigation callback for detected Bilibili video")
                                    }
                                    navigationInProgress.compareAndSet(false, true) -> {
                                        // First callback for a new navigation. Explicit address-bar and
                                        // refresh requests have already reset state before calling loadUrl/reload.
                                        resetForNavigation(url)
                                        pageLoadToken++
                                    }
                                    else -> {
                                        // Redirect continuation: update address/UA only.
                                        // Do not clear candidates or reinitialize the detection generation.
                                        applyPikpakUserAgent(url)
                                        currentPage = url
                                        address = url
                                    }
                                }
                                // 普通视频站仍尽早挂 hook；PikPak 分享页先让官方 SPA 完成初始化，
                                // 再挂接后续人工密码验证/文件夹点击产生的请求，避免过早改写 fetch/XHR。
                                if (screenActive.get() && PikpakShare.parseShareId(url) == null) {
                                    runCatching { view.evaluateJavascript(NET_HOOK_SCRIPT, null) }
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                if (!screenActive.get() || url != currentPage) return
                                val completedGeneration = pageGeneration.get()
                                val duplicateCompletedBilibiliPage =
                                    lastCompletedNavigationGeneration.get() == completedGeneration &&
                                        WebVideoProbePolicy.isDuplicateCompletedNavigation(
                                            targetUrl = url,
                                            currentPageUrl = currentPage,
                                            lastCompletedUrl = lastCompletedNavigationUrl.get(),
                                        )
                                if (duplicateCompletedBilibiliPage) {
                                    // Do not reset the status or restart the recurring JS probe for
                                    // the same already-finished Bilibili video.
                                    navigationInProgress.set(false)
                                    address = url
                                    Log.d(TAG_WEB, "Ignoring repeated finish callback for completed Bilibili video")
                                    return
                                }
                                pageLoading = false
                                navigationInProgress.set(false)
                                lastCompletedNavigationUrl.set(url)
                                lastCompletedNavigationGeneration.set(completedGeneration)
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
                                val isPikpakSharePage = PikpakShare.parseShareId(url) != null
                                if (isPikpakSharePage) {
                                    // PikPak 是依赖异步初始化的单页应用。不要在 onPageFinished 回调里立即改写
                                    // fetch/XHR；先给官方页面留出稳定渲染时间，再安装只读响应监听。
                                    val renderGeneration = pageGeneration.get()
                                    mainHandler.postDelayed({
                                        if (!screenActive.get() ||
                                            pageGeneration.get() != renderGeneration ||
                                            currentPage != url
                                        ) {
                                            return@postDelayed
                                        }
                                        runCatching { view.evaluateJavascript(NET_HOOK_SCRIPT, null) }
                                            .onFailure { Log.w(TAG_WEB, "PikPak request hook injection failed", it) }
                                    }, 1500L)
                                    mainHandler.postDelayed({
                                        if (!screenActive.get() || pageGeneration.get() != renderGeneration || currentPage != url) {
                                            return@postDelayed
                                        }
                                        val inspectScript = """
                                            (function(){
                                              var b = document.body;
                                              var r = document.querySelector('#app,#root,#__next,[data-reactroot]');
                                              return {
                                                title: document.title || '',
                                                readyState: document.readyState || '',
                                                bodyTextLength: b ? ((b.innerText || '').trim().length) : 0,
                                                bodyChildCount: b ? b.children.length : 0,
                                                bodyHtmlLength: b ? (b.innerHTML || '').length : 0,
                                                appRootFound: !!r,
                                                appRootChildCount: r ? r.children.length : -1,
                                                appRootHtmlLength: r ? (r.innerHTML || '').length : -1
                                              };
                                            })()
                                        """.trimIndent()
                                        view.evaluateJavascript(inspectScript) { raw ->
                                            if (screenActive.get() && pageGeneration.get() == renderGeneration && currentPage == url) {
                                                val metrics = runCatching { org.json.JSONObject(raw ?: "{}") }.getOrNull()
                                                if (metrics != null) {
                                                    val bodyTextLength = metrics.optInt("bodyTextLength", 0)
                                                    val bodyChildCount = metrics.optInt("bodyChildCount", 0)
                                                    val bodyHtmlLength = metrics.optInt("bodyHtmlLength", 0)
                                                    val appRootFound = metrics.optBoolean("appRootFound", false)
                                                    val appRootChildCount = metrics.optInt("appRootChildCount", -1)
                                                    val appRootHtmlLength = metrics.optInt("appRootHtmlLength", -1)
                                                    val likelyBlank = bodyTextLength == 0 && (
                                                        (appRootFound && appRootChildCount == 0 && appRootHtmlLength < 80) ||
                                                            (!appRootFound && bodyChildCount == 0 && bodyHtmlLength < 150)
                                                        )
                                                    val renderDiagnostics = mapOf(
                                                        "页面主机名" to webSourceHost(url),
                                                        "页面标题" to metrics.optString("title"),
                                                        "文档状态" to metrics.optString("readyState"),
                                                        "正文文本长度" to bodyTextLength.toString(),
                                                        "正文子元素数" to bodyChildCount.toString(),
                                                        "正文 HTML 长度" to bodyHtmlLength.toString(),
                                                        "应用根节点是否存在" to appRootFound.toString(),
                                                        "应用根节点子元素数" to appRootChildCount.toString(),
                                                        "应用根节点 HTML 长度" to appRootHtmlLength.toString(),
                                                        "WebView 内核版本" to runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull().orEmpty().ifBlank { "未知" },
                                                        "UA 是否仍带 WebView 标记" to view.settings.userAgentString.contains("; wv)", ignoreCase = true).toString(),
                                                        "页面导航代数" to renderGeneration.toString(),
                                                    )
                                                    val renderIssueKey = io.translatetube.local.util.AppLogStore.stableIssueKey(
                                                        "PikPakPageRender", webSourceIssueIdentity(url),
                                                    )
                                                    if (likelyBlank) {
                                                        io.translatetube.local.util.AppLogStore.warning(
                                                            context = appCtx,
                                                            tag = "PikPakPageRender",
                                                            message = "PikPak 页面加载回调已完成，但页面主体疑似未渲染（白屏）",
                                                            diagnosticContext = renderDiagnostics,
                                                            issueKey = renderIssueKey,
                                                        )
                                                        // Do not auto-reload a PikPak SPA based on a single empty-DOM sample:
                                                        // the app may still be mounting, and reload discards password-entry state.
                                                        status = "PikPak 页面主体暂未完成渲染，已保留当前网页；可点右上角刷新重试。"
                                                    } else {
                                                        io.translatetube.local.util.AppLogStore.resolveIssue(
                                                            context = appCtx,
                                                            issueKey = renderIssueKey,
                                                            message = "PikPak 页面主体已正常渲染",
                                                            diagnosticContext = renderDiagnostics,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }, 3500L)
                                }
                                // The generic touch-target patch mutates DOM styles and is not needed
                                // on a PikPak file-list page; keep it for actual video/player websites.
                                if (!isPikpakSharePage) {
                                    view.evaluateJavascript(PROBE_SCRIPT, null)
                                }
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
                                        val isNewCandidate = previousCandidates.none { it.url == candidate.url }
                                        val addedCount = if (isNewCandidate) 1 else 0
                                        val mergedCandidates = WebVideoProbePolicy.mergeCandidates(
                                            previousCandidates,
                                            listOf(candidate),
                                        )
                                        // Resource requests can repeat for every HLS segment/poll;
                                        // publish candidate state only when content actually changes.
                                        if (mergedCandidates != previousCandidates) {
                                            candidatesState.value = mergedCandidates
                                        }
                                        // The per-candidate type classification below carries individual fingerprints.
                                        // Only log the first network-intercept discovery here to avoid flooding a bounded log
                                        // with a separate identical event for every segment/resource on a complex page.
                                        if (isNewCandidate && previousCandidates.isEmpty()) {
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
                                        if (isNewCandidate) {
                                            status = "已从网页网络请求中检测到媒体资源。"
                                        }
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
                                            navigationInProgress.set(false)
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
                                if (request.isForMainFrame) {
                                    val failedStatus = errorResponse.statusCode
                                    val failedGeneration = pageGeneration.get()
                                    mainHandler.postDelayed({
                                        if (screenActive.get() && pageGeneration.get() == failedGeneration && currentPage == failedUrl) {
                                            pageLoading = false
                                            navigationInProgress.set(false)
                                            status = if (PikpakShare.parseShareId(failedUrl) != null) {
                                                "PikPak 官方页面返回 HTTP $failedStatus，服务器可能限制了访问；请点刷新重试。"
                                            } else {
                                                "网页返回 HTTP $failedStatus，请检查链接或稍后刷新重试。"
                                            }
                                        }
                                    }, 350L)
                                }
                            }

                        }
                        val initial = WebMediaUrlPolicy.normalizePageUrl(initialUrl)
                        if (initial != null) {
                            // 初次进入页面也必须先切换 PikPak 专用移动浏览器 UA。
                            // 否则首次请求仍带系统 WebView 的 "; wv)" 标记，部分 PikPak
                            // 前端会进入降级分支，出现空白页；后续手动导航才应用 UA 已经太晚。
                            applyPikpakUserAgent(initial)
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


            val minPanelHeight = 8.dp.coerceAtMost(maxHeight)
            val maxPanelHeight = (maxHeight * 0.92f)
                .coerceAtLeast(minPanelHeight)
                .coerceAtMost(maxHeight)
            // 点底部把手展开时，默认显示约两张视频源卡片；小屏幕自动适配可用高度。
            val panelExpandedHeight = 260.dp.coerceAtMost(maxPanelHeight)
            val targetPanelHeight = sourcePanelHeightDp.dp.coerceIn(minPanelHeight, maxPanelHeight)
            val panelCollapsed = targetPanelHeight <= minPanelHeight + 1.dp
            val panelContentVisible = !panelCollapsed && targetPanelHeight > minPanelHeight + 8.dp
            // pointerInput 会跨多次 Compose 重组继续运行；通过 updatedState 读取当前实际显示状态，
            // 确保无论面板被拖到多高，点击顶部横线都能按屏幕上的开/收状态完全收起。
            val latestPanelCollapsed by rememberUpdatedState(panelCollapsed)
            val latestPanelExpandedHeight by rememberUpdatedState(panelExpandedHeight.value)
            val density = LocalDensity.current
            // Scaffold 内容默认避开系统导航栏；偏移一个导航栏 inset，
            // 让收起态的横线贴到物理屏幕底边，而不是停在应用内容底边。
            val bottomSystemInset = with(density) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            }
            // 点击和拖动共用同一个手势识别器：未越过系统 touch slop 且正常抬手才算点击；
            // 越过阈值后立即进入拖动模式，本次手势结束绝不再触发点击，防止拖动时误隐藏。
            val panelHandleModifier = Modifier.pointerInput(minPanelHeight, maxPanelHeight, density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val touchSlop = viewConfiguration.touchSlop
                    var totalX = 0f
                    var totalY = 0f
                    var dragStarted = false
                    var horizontalGesture = false
                    var finished = false

                    // positionChange() 得到的是手指真实位移，不受把手随面板移动影响
                    fun applyDrag(dyPx: Float) {
                        val deltaDp = with(density) { dyPx.toDp().value }
                        sourcePanelHeightDp = (sourcePanelHeightDp - deltaDp)
                            .coerceIn(minPanelHeight.value, maxPanelHeight.value)
                    }

                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()

                        if (!dragStarted && !horizontalGesture) {
                            totalX += delta.x
                            totalY += delta.y
                            when {
                                abs(totalX) > touchSlop && abs(totalX) > abs(totalY) -> {
                                    horizontalGesture = true
                                }
                                abs(totalY) > touchSlop -> {
                                    dragStarted = true
                                    applyDrag(if (totalY > 0f) totalY - touchSlop else totalY + touchSlop)
                                    change.consume()
                                }
                            }
                        } else if (dragStarted && change.pressed) {
                            applyDrag(delta.y)
                            change.consume()
                        }

                        if (!change.pressed) finished = true
                    }

                    if (finished && !dragStarted && !horizontalGesture) {
                        // 点击只允许两种状态切换：当前 UI 已展开（包含任意拖动高度）就完全收起；
                        // 当前 UI 已收起才展开到双卡片默认高度。超过 touch slop 的手势不会走到这里。
                        sourcePanelHeightDp = if (latestPanelCollapsed) {
                            latestPanelExpandedHeight
                        } else {
                            minPanelHeight.value
                        }
                    }
                }
            }

            // 面板下拉时，固定网页窗口与面板之间露出的空白区域专门承载加载、
            // 媒体检测、候选验证和 yt-dlp 状态；覆盖面板本身只呈现来源列表。
            val statusGapHeight = (panelPeekHeight - targetPanelHeight).coerceAtLeast(0.dp)
            if (statusGapHeight >= 36.dp) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = fixedWebHeight)
                        .fillMaxWidth()
                        .height(statusGapHeight)
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AnimatedVisibility(
                        visible = pageLoading || ytDlpBusy || ytDlpUpdatingRules,
                        enter = fadeIn(animationSpec = tween(150)) +
                            expandVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)),
                        exit = fadeOut(animationSpec = tween(110)) +
                            shrinkVertically(animationSpec = tween(150)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                when {
                                    ytDlpUpdatingRules -> "正在更新 yt-dlp 解析规则（约 30MB，请稍候）…"
                                    ytDlpBusy -> "yt-dlp 正在解析视频资源…"
                                    else -> "正在加载网页并检测视频源…"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!ytDlpUpdatingRules) {
                                TextButton(
                                    onClick = { cancelWebParsing() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(if (ytDlpBusy) "取消解析" else "停止加载", maxLines = 1)
                                }
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
                                if (ytDlpDetailsExpanded) ytDlpHint.orEmpty() else "yt-dlp 解析异常 · 点击查看原因",
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { ytDlpDetailsExpanded = !ytDlpDetailsExpanded },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = if (ytDlpDetailsExpanded) 4 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (io.translatetube.local.yt.YtDlpRepo.isEligible(currentPage)) {
                                TextButton(
                                    modifier = Modifier.heightIn(min = 30.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    enabled = !ytDlpUpdatingRules && !ytDlpBusy,
                                    onClick = {
                                        ytDlpUpdatingRules = true
                                        ytDlpHint = null
                                        status = "正在更新 yt-dlp 解析规则（约 30MB，请稍候）…"
                                        ytScope.launch(Dispatchers.IO) {
                                            try {
                                                val message = io.translatetube.local.yt.YtDlpRepo.updateRules(appCtx)
                                                mainHandler.post {
                                                    ytDlpUpdatingRules = false
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
                                                    ytDlpUpdatingRules = false
                                                    ytDlpHint = "规则更新失败：${e.message ?: "网络错误"}"
                                                    status = "规则更新失败，可稍后重试。"
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(if (ytDlpUpdatingRules) "更新中…" else "更新规则", maxLines = 1)
                                }
                            }
                        }
                    }

                    if (!pageLoading && !ytDlpBusy && !ytDlpUpdatingRules) {
                        if (candidatesState.value.isNotEmpty()) {
                            Text(
                                "${candidatesState.value.size} 个候选源 · ${verifiedUrls.size} 个已验证",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (status.isNotBlank()) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ytDlpHint != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else if (candidatesState.value.isEmpty()) {
                            Text(
                                "等待检测视频源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (
                            candidatesState.value.isEmpty() &&
                            ytDlpHint == null &&
                            ytDlpAutoAttempts == 0 &&
                            PikpakShare.parseShareId(currentPage) == null &&
                            WebMediaUrlPolicy.normalizePageUrl(currentPage) != null
                        ) {
                            Text(
                                "候选源验证中，必要时将尝试备用解析。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // 分享页的加载/清单状态也放在空白区域，视频源面板里只留可操作的文件与候选源。
                    if (PikpakShare.parseShareId(currentPage) != null || pikpakShareId != null) {
                        val pikpakFolders = pikpakEntries.filter { it.isFolder }
                        val pikpakVideos = pikpakEntries.filter { it.isVideo && it.downloadUrl.isNotBlank() }
                        Text(
                            when {
                                pikpakBusy -> "正在读取 PikPak 分享清单…"
                                pikpakNeedsPasscode -> "请在上方 PikPak 官方网页输入提取码并完成验证。"
                                pikpakEntries.isEmpty() -> "正在读取 PikPak 文件清单…"
                                else -> "PikPak：${pikpakVideos.size} 个视频 · ${pikpakFolders.size} 个文件夹"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pikpakNeedsPasscode) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pikpakNotice != null) {
                            Text(
                                pikpakNotice.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pikpakNeedsPasscode) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!panelCollapsed) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(targetPanelHeight),
                    shape = RoundedCornerShape(
                        topStart = 22.dp,
                        topEnd = 22.dp,
                        bottomStart = 0.dp,
                        bottomEnd = 0.dp,
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 14.dp),
                ) {
                Column(Modifier.fillMaxSize()) {
                    if (panelContentVisible) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(start = 14.dp, top = 28.dp, end = 14.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                // ---- PikPak 分享清单区：识别到分享链接或已捕获清单时显示 ----
                if (PikpakShare.parseShareId(currentPage) != null || pikpakShareId != null) {
                    val pikpakFolders = pikpakEntries.filter { it.isFolder }
                        .sortedBy { it.name.lowercase() }
                    val pikpakVideos = pikpakEntries.filter { it.isVideo && it.downloadUrl.isNotBlank() }
                    if (pikpakNeedsPasscode) {
                        Text(
                            "请在上方官方分享网页的提取码输入框中手动验证。验证成功后，应用会自动继续读取；不要在此处重复输入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(enabled = !pikpakBusy, onClick = { expandPikpak("") }) {
                        Text(
                            if (pikpakEntries.isEmpty()) "读取/刷新清单" else "展开全部文件夹"
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
                                            WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
                                            WebCandidateAudioHintKind.MUXED_DIRECT -> 1
                                            // Confirmed silent: nothing to transcribe, so sink it.
                                            WebCandidateAudioHintKind.NO_AUDIO -> -1
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
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(displayCandidates, key = { it.url }) { candidate ->
                            val sourceHint = sourceHints[candidate.url]
                                ?: WebCandidateVerifier.initialAudioHint(candidate.url)
                            var showMediaDetails by remember(candidate.url) { mutableStateOf(false) }
                            val reason = when {
                                candidate.drmProtected -> "检测到 EME/DRM 标记，不能提取或绕过受保护的媒体音轨。"
                                else -> WebMediaUrlPolicy.unsupportedReason(candidate.url)
                            }
                            val detailRows = buildList {
                                addAll(sourceHint.detailRows())
                                add("来源校验" to if (candidate.url in verifiedUrls) "已验证可访问" else "尚未验证")
                                reason?.let { add("播放限制" to it) }
                            }
                            // 只有已识别出的具体音轨类型显示红色；检测中、待确认和检测失败使用中性样式。
                            val audioTypeRecognized = sourceHint.kind in setOf(
                                WebCandidateAudioHintKind.INDEPENDENT,
                                WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
                                WebCandidateAudioHintKind.MUXED_DIRECT,
                                WebCandidateAudioHintKind.NO_AUDIO,
                            )
                            val audioChipContainer = if (audioTypeRecognized) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val audioChipTextColor = if (audioTypeRecognized) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val resolutionSummary = sourceHint.resolutionSummary()
                            val resolutionRecognized = resolutionSummary != "待识别" &&
                                resolutionSummary != "无视频"
                            val resolutionChipContainer = if (resolutionRecognized) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val resolutionChipTextColor = if (resolutionRecognized) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Card(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = tween(durationMillis = 160),
                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        fadeOutSpec = tween(durationMillis = 120),
                                    )
                                    .fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        candidate.title.ifBlank { pageTitle.ifBlank { "网页视频" } },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        candidate.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            sourceHint.compactSummary(),
                                            modifier = Modifier
                                                .background(
                                                    color = audioChipContainer,
                                                    shape = RoundedCornerShape(8.dp),
                                                )
                                                .padding(horizontal = 9.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = audioChipTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            resolutionSummary,
                                            modifier = Modifier
                                                .background(
                                                    color = resolutionChipContainer,
                                                    shape = RoundedCornerShape(8.dp),
                                                )
                                                .padding(horizontal = 9.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = resolutionChipTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (reason != null) {
                                            Text(
                                                "播放限制",
                                                modifier = Modifier
                                                    .background(
                                                        color = MaterialTheme.colorScheme.errorContainer,
                                                        shape = RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
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
                                                        "视频码率 bps" to (hint.mediaInfo?.videoBitrateBps?.toString() ?: "未检测到"),
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
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showMediaDetails = !showMediaDetails }
                                            .padding(vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "更多",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = if (showMediaDetails) Icons.Filled.KeyboardArrowUp
                                                else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = if (showMediaDetails) "收起更多信息" else "展开更多信息",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    AnimatedVisibility(visible = showMediaDetails) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 2.dp, bottom = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            detailRows.forEach { (label, value) ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.Top,
                                                ) {
                                                    Text(
                                                        label,
                                                        modifier = Modifier.widthIn(min = 76.dp, max = 104.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        value,
                                                        modifier = Modifier.weight(1f),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                            }
                                            if (sourceHint.kind == WebCandidateAudioHintKind.FAILED) {
                                                Text(
                                                    "重新检测",
                                                    modifier = Modifier
                                                        .clickable {
                                                            sourceHintAttempts = sourceHintAttempts - candidate.url
                                                            sourceHints = sourceHints +
                                                                (candidate.url to WebCandidateVerifier.initialAudioHint(candidate.url))
                                                        }
                                                        .padding(vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
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

            // 这个把手 Box 在展开/收起之间保持同一个组合节点，避免拖动中途被销毁。
            // 收起时只有透明触控热区；可见横线贴在屏幕底边，不绘制面板区域。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (panelCollapsed) 24.dp else targetPanelHeight)
                    .offset(y = if (panelCollapsed) bottomSystemInset else 0.dp),
                contentAlignment = if (panelCollapsed) Alignment.BottomCenter else Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(24.dp)
                        .then(panelHandleModifier),
                    contentAlignment = if (panelCollapsed) Alignment.BottomCenter else Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(
                                width = if (panelCollapsed) 48.dp else 36.dp,
                                height = if (panelCollapsed) 6.dp else 4.dp,
                            )
                            .background(
                                color = if (panelCollapsed) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(if (panelCollapsed) 3.dp else 2.dp),
                            ),
                    )
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
