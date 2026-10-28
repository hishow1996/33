package io.translatetube.local.ui.screens

import java.io.OutputStreamWriter

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.translatetube.local.floating.FloatingHostLayout
import io.translatetube.local.floating.FloatingManager
import io.translatetube.local.floating.FloatingOverlayLifecycleOwner
import io.translatetube.local.player.PlaybackService
import io.translatetube.local.player.PlayerUiState
import io.translatetube.local.player.Phase
import io.translatetube.local.player.VideoSourceKind
import io.translatetube.local.player.VideoProcessingFlowPolicy
import io.translatetube.local.subs.SubLine
import io.translatetube.local.web.WebMediaUrlPolicy
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.yt.SubtitleTrackOption
import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.translate.LangMap
import io.translatetube.local.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private enum class SubtitleExportFormat { CHINESE_SRT, BILINGUAL_SRT, BILINGUAL_VTT }

@Composable
fun PlayerScreen(
    videoId: String?,
    localUri: android.net.Uri?,
    onBack: () -> Unit,
    onGoSettings: () -> Unit = {},
    mediaUrl: String? = null,
    mediaTitle: String = "",
    mediaReferrer: String = "",
    mediaCookie: String? = null,
    mediaUserAgent: String? = null,
    requestToken: Long = 0L,
    showPlayerWhileRestoringHistory: Boolean = false,
) {
    val ctx = LocalContext.current
    val settingsPrefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val selectedTtsEngine = settingsPrefs.getString("tts_engine", "edge") ?: "edge"
    val systemVoiceCatalog = io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog(
        settingsPrefs.getString(io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY, null),
    )
    val voiceOptions: List<Pair<Int, String>> = when (selectedTtsEngine) {
        "system" -> buildList {
            add(-1 to io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.displayLabel(-1, "system", systemVoiceCatalog))
            systemVoiceCatalog.drop(1).forEachIndexed { index, voiceName -> add(index to voiceName) }
        }
        "piper" -> emptyList()
        "web" -> listOf(-1 to "在线语音 · 默认音色")
        else -> buildList {
            add(-1 to "Kokoro 默认音色")
            (0..53).forEach { add(it to "Kokoro 音色 $it") }
        }
    }
    val voiceSelectionEnabled = selectedTtsEngine != "piper" && selectedTtsEngine != "web" && voiceOptions.size > 1
    val state by PlaybackService.ui.collectAsState()
    val listState = rememberLazyListState()
    var subtitlesVisible by remember { mutableStateOf(true) }
    var advancedMenuExpanded by remember { mutableStateOf(false) }
    var showSubtitleDownloadDialog by remember { mutableStateOf(false) }
    var pendingSubtitleTrack by remember { mutableStateOf<SubtitleTrackOption?>(null) }
    var pendingSubtitleExportContent by remember { mutableStateOf<String?>(null) }
    var showPersonVoiceDialog by remember { mutableStateOf(false) }
    var personVoiceMenuExpanded by remember { mutableStateOf(false) }
    var personTargetIndex by remember { mutableIntStateOf(-1) }
    var selectedPersonVoiceId by remember { mutableIntStateOf(-1) }
    val subtitleExportScope = rememberCoroutineScope()
    val isSpeakerBusy = state.progress.contains("人物识别") ||
        state.progress.contains("人物说话") ||
        state.progress.contains("分离")

    // 错误时允许用户显式重试同一个视频；不监听 phase，避免失败后自动重试循环。
    var retryNonce by remember { mutableStateOf(0) }
    var handledRetryNonce by remember { mutableStateOf(0) }

    val requestKey = when {
        videoId != null -> "youtube:$videoId"
        !mediaUrl.isNullOrBlank() -> "remote:$mediaUrl"
        else -> "local:${localUri ?: ""}"
    }
    var dismissedNoModelHint by remember(requestKey) { mutableStateOf<String?>(null) }

    // Keep this sticky for the current video. A subtitle seek can briefly set isPlaying=false
    // while the stream buffers; that is not a reason to return to the parsing page.
    var playbackHasStarted by remember(requestKey) { mutableStateOf(false) }
    LaunchedEffect(requestKey, state.key, state.isPlaying, state.phase) {
        if (
            state.key == requestKey &&
            state.isPlaying &&
            state.phase in setOf(Phase.SYNTHESIZING, Phase.READY)
        ) {
            playbackHasStarted = true
        }
    }

    fun retryCurrentTask() {
        val key = requestKey
        val current = PlaybackService.ui.value
        val service = PlaybackService.instance
        val canResumeExistingWork = current.key == key &&
            current.phase == Phase.ERROR &&
            current.lines.isNotEmpty() && current.sourceLang.isNotBlank()
        if (canResumeExistingWork && service?.isReady == true) {
            service.open(key, videoId, localUri, resumeState = current, remoteMediaUrl = mediaUrl, remoteMediaTitle = mediaTitle, remoteMediaReferrer = mediaReferrer, remoteMediaCookie = mediaCookie, remoteMediaUserAgent = mediaUserAgent, requestToken = requestToken)
        } else {
            retryNonce++
        }
    }

    // 打开视频：先确保服务在跑，再下发任务
    LaunchedEffect(videoId, localUri, mediaUrl, mediaTitle, mediaReferrer, mediaCookie, mediaUserAgent, requestToken, retryNonce) {
        val key = requestKey
        val retryRequested = retryNonce != handledRetryNonce
        // A repeated tap on the same history row has the same media key but a new request token.
        // Reopen that session so the latest selected TTS engine is actually initialized. Returning
        // from Settings carries the same token, so it must not restart the video a second time.
        val newPlaybackRequest = requestToken > 0L &&
            PlaybackService.instance?.isOpenRequestActive(key, requestToken) != true
        if (state.key != key || retryRequested || PlaybackService.instance?.isReady != true || newPlaybackRequest) {
            if (retryRequested) handledRetryNonce = retryNonce
            try {
                var s = PlaybackService.instance
                if (retryRequested && s?.isReady == false) {
                    // 初始化失败的旧实例可能尚在销毁过程中，先等待它退出再重启服务。
                    ctx.stopService(android.content.Intent(ctx, PlaybackService::class.java))
                    var stopTries = 0
                    while (PlaybackService.instance != null && stopTries < 20) {
                        delay(100)
                        stopTries++
                    }
                    s = null
                }
                if (s == null) {
                    androidx.core.content.ContextCompat.startForegroundService(
                        ctx, android.content.Intent(ctx, PlaybackService::class.java)
                    )
                }
                var tries = 0
                while (tries < 50) {
                    if (PlaybackService.instance?.isReady == true) break
                    delay(100)
                    tries++
                }
                s = PlaybackService.instance
                if (s == null || !s.isReady) {
                    PlaybackService.ui.update {
                        it.copy(
                            phase = Phase.ERROR,
                            progress = "",
                            error = "播放器服务启动失败或超时，请检查系统状态后点击重试"
                        )
                    }
                } else {
                    val failedState = PlaybackService.ui.value
                    val resumeExistingWork = if (
                        retryRequested && failedState.key == key &&
                        failedState.phase == Phase.ERROR &&
                        failedState.lines.isNotEmpty() && failedState.sourceLang.isNotBlank()
                    ) failedState else null
                    s.open(key, videoId, localUri, resumeState = resumeExistingWork, remoteMediaUrl = mediaUrl, remoteMediaTitle = mediaTitle, remoteMediaReferrer = mediaReferrer, remoteMediaCookie = mediaCookie, remoteMediaUserAgent = mediaUserAgent, requestToken = requestToken)
                }
            } catch (e: Throwable) {
                // Do not let a non-fatal runtime/linkage error in service startup terminate the
                // Compose coroutine and process. VM-fatal conditions must still propagate.
                if (e is VirtualMachineError || e is ThreadDeath) throw e
                io.translatetube.local.util.AppLogStore.error(
                    ctx.applicationContext,
                    "PlayerScreen",
                    "启动播放器服务或恢复播放任务失败",
                    e,
                    diagnosticContext = mapOf(
                        "视频来源" to when {
                            videoId != null -> "YouTube"
                            !mediaUrl.isNullOrBlank() -> "网页视频/远程媒体"
                            localUri != null -> "本地视频"
                            else -> "未知来源"
                        },
                        "当前处理阶段" to PlaybackService.ui.value.phase.name,
                        "当前处理进度" to PlaybackService.ui.value.progress.take(200),
                        "当前翻译引擎" to PlaybackService.ui.value.engineName.ifBlank { "未确定" },
                        "当前配音引擎配置" to selectedTtsEngine,
                        "本次播放请求标记" to requestToken.toString(),
                        "重试计数" to retryNonce.toString(),
                        "播放器服务是否存在" to (PlaybackService.instance != null).toString(),
                    ),
                )
android.util.Log.e("PlayerScreen", "Unable to start playback service", e)
                PlaybackService.ui.update {
                    it.copy(
                        phase = Phase.ERROR,
                        progress = "",
                        error = "无法启动播放器（${e.javaClass.simpleName}）：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    val floating = remember { FloatingManager(ctx) }
    var isFloating by remember { mutableStateOf(false) }
    // 悬浮窗自己的生命周期：不能借用 Activity 的，否则切到桌面后 Compose 会暂停刷新悬浮窗里的按钮层。
    val overlayOwnerHolder = remember { arrayOfNulls<FloatingOverlayLifecycleOwner>(1) }
    val tv = remember { TextureView(ctx) }
    val frame = remember { FrameLayout(ctx) }
    val surfaceAttachScope = rememberCoroutineScope()
    val surfaceAttachJob = remember(tv) { arrayOfNulls<kotlinx.coroutines.Job>(1) }

    // A view can be attached before its SurfaceTexture exists or before layout gives it
    // non-zero dimensions. Both landscape and portrait videos need a real output surface before
    // Media3 binds it; wait for service readiness AND surface readiness, not just window attachment.
    fun attachSurfaceWhenReady() {
        if (!tv.isAttachedToWindow) return
        surfaceAttachJob[0]?.cancel()
        surfaceAttachJob[0] = surfaceAttachScope.launch {
            var tries = 0
            while (tries < 100 && tv.isAttachedToWindow) {
                val service = PlaybackService.instance
                val surfaceReady = tv.isAvailable && tv.width > 0 && tv.height > 0
                if (service?.isReady == true && surfaceReady) {
                    service.attachSurface(tv)
                    return@launch
                }
                tries++
                delay(100)
            }
            if (tv.isAttachedToWindow) {
                val serviceReady = PlaybackService.instance?.isReady == true
                android.util.Log.w(
                    "PlayerScreen",
                    "Video surface initialization timed out: serviceReady=" + serviceReady +
                        ", textureAvailable=" + tv.isAvailable +
                        ", size=" + tv.width + "x" + tv.height,
                )
            }
        }
    }

    // Bind only after TextureView is attached, and do not lose the request if service startup lags.
    val surfaceAttachListener = remember(tv) {
        object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) {
                attachSurfaceWhenReady()
            }

            override fun onViewDetachedFromWindow(view: android.view.View) {
                surfaceAttachJob[0]?.cancel()
                surfaceAttachJob[0] = null
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(tv) {
        tv.addOnAttachStateChangeListener(surfaceAttachListener)
        if (tv.isAttachedToWindow) attachSurfaceWhenReady()
        onDispose {
            tv.removeOnAttachStateChangeListener(surfaceAttachListener)
            surfaceAttachJob[0]?.cancel()
            surfaceAttachJob[0] = null
        }
    }

    // Reparse switches between the processing UI and player UI. AndroidView can be
    // recreated while this remembered FrameLayout still owns the TextureView.
    // Move the surface only when needed; never add the same child twice.
    fun moveTextureViewTo(target: FrameLayout) {
        if (tv.parent === target) {
            val lp = tv.layoutParams as? FrameLayout.LayoutParams
            if (lp == null || lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                tv.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return
        }
        (tv.parent as? ViewGroup)?.removeView(tv)
        if (tv.parent == null) {
            target.addView(
                tv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            android.util.Log.w(
                "PlayerScreen",
                "Skip TextureView reparent: current parent=" + tv.parent?.javaClass?.simpleName,
            )
        }
    }

    // 从后台回到播放页时重新绑定画面：后台期间 TextureView 的画面表面会被系统销毁，
    // 不重新绑定就会一直黑屏。
    val resumeLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(resumeLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                attachSurfaceWhenReady()
            }
        }
        resumeLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { resumeLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 播放页屏幕常亮
    val keepView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        keepView.keepScreenOn = true
        onDispose { keepView.keepScreenOn = false }
    }

    // 退出前收浮窗 + 存进度
    fun exitFloating() {
        if (isFloating) {
            floating.hide()
            overlayOwnerHolder[0]?.destroy()
            overlayOwnerHolder[0] = null
            moveTextureViewTo(frame)
            isFloating = false
        }
    }

    fun startFloatingWindow() {
        // 全局悬浮窗使用 TYPE_APPLICATION_OVERLAY：未授权时打开系统悬浮窗权限页；
        // 已授权则直接创建窗口，不再跳转设置。
        if (!floating.canDraw()) {
            val permissionUri = android.net.Uri.parse("package:${ctx.packageName}")
            val permissionPageOpened = runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        permissionUri,
                    )
                )
            }.isSuccess
            if (!permissionPageOpened) {
                // 少数系统版本不提供专用入口时，退回应用详情页供用户开启权限。
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            permissionUri,
                        )
                    )
                }
            }
            return
        }
        // 根容器把每个触摸事件的屏幕绝对坐标报给 FloatingManager，拖动/缩放全部基于它。
        val host = FloatingHostLayout(ctx).apply {
            onRawTouch = { event -> floating.onRawTouch(event) }
        }
        moveTextureViewTo(host)
        // WindowManager overlays are detached from the Activity's decor view tree.
        // ComposeView requires both lifecycle and saved-state owners before it is attached.
        // Use the overlay's own always-RESUMED owner: the Activity's one stops when the user goes
        // to the home screen, which freezes recomposition of the controls inside the overlay.
        overlayOwnerHolder[0]?.destroy()
        val overlayOwner = FloatingOverlayLifecycleOwner()
        overlayOwnerHolder[0] = overlayOwner
        host.setViewTreeLifecycleOwner(overlayOwner)
        host.setViewTreeSavedStateRegistryOwner(overlayOwner)
        overlayOwner.resume()
        val controlsView = ComposeView(ctx).apply {
            setContent {
                FloatingPlayerControls(
                    onBeginWindowDrag = { floating.beginPointerDrag() },
                    onMoveWindow = { floating.moveToPointer() },
                    onResizeWindow = { floating.resizeToPointer() },
                    onFullscreen = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    ctx,
                                    io.translatetube.local.MainActivity::class.java,
                                ).apply {
                                    addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                    )
                                },
                            )
                        }
                        exitFloating()
                    },
                    onClose = {
                        PlaybackService.instance?.pausePlayback()
                        PlaybackService.instance?.saveProgress()
                        exitFloating()
                    },
                )
            }
        }
        host.addView(
            controlsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // 显式保持按钮 ComposeView 位于视频 TextureView 上方，确保全局悬浮层可接收点击。
        controlsView.bringToFront()
        // 直接使用播放器回报的视频真实宽高比，悬浮窗初始化和后续缩放共用同一比例。
        val shown = floating.show(host, 640, 360, state.videoAspect)
        if (shown && floating.isShowing()) {
            isFloating = true
        } else {
            // WindowManager can reject an overlay even after permission was granted. Never leave
            // the TextureView parented to a detached host or the player turns black.
            overlayOwner.destroy()
            overlayOwnerHolder[0] = null
            moveTextureViewTo(frame)
            isFloating = false
            android.widget.Toast.makeText(
                ctx,
                "悬浮窗创建失败，已恢复播放器画面",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // 系统返回键：存进度回主页，而不是退出应用
    androidx.activity.compose.BackHandler {
        exitFloating()
        PlaybackService.instance?.saveProgress()
        onBack()
    }

    // 导入原文字幕：沿用既有的自动翻译+配音流程。
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { PlaybackService.instance?.importSubtitles(it) }
    }
    // 导入已经翻译好的字幕：直接配音，不再调用翻译引擎。
    val translatedSubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { PlaybackService.instance?.importTranslatedSubtitles(it) }
    }
    // Import a translation for the currently waiting pipeline, preserving its original subtitle timeline.
    val manualTranslatedSubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { PlaybackService.instance?.importManualTranslatedSubtitle(it) }
    }
    val subtitleFileSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri ->
        val track = pendingSubtitleTrack
        pendingSubtitleTrack = null
        if (uri != null && track != null) PlaybackService.instance?.downloadSubtitle(track, uri)
    }
    fun requestSubtitleDownload(track: SubtitleTrackOption) {
        pendingSubtitleTrack = track
        val base = state.title.ifBlank { "video" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(48)
        val lang = track.languageTag.replace(Regex("[^A-Za-z0-9_-]"), "_")
        subtitleFileSaver.launch(base + "_" + lang + ".vtt")
    }

    fun saveSubtitleExport(uri: android.net.Uri?) {
        val content = pendingSubtitleExportContent
        pendingSubtitleExportContent = null
        if (uri == null || content.isNullOrBlank()) return
        subtitleExportScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val output = ctx.contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException("无法打开导出文件")
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        writer.write(content)
                    }
                }.exceptionOrNull()
            }
            val message = if (error == null) {
                "字幕已导出"
            } else {
                "字幕导出失败：" + (error.message ?: error.javaClass.simpleName)
            }
            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val subtitleSrtSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip"),
    ) { uri -> saveSubtitleExport(uri) }
    val subtitleBilingualSrtSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip"),
    ) { uri -> saveSubtitleExport(uri) }
    val subtitleBilingualVttSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vtt"),
    ) { uri -> saveSubtitleExport(uri) }

    fun requestSubtitleExport(format: SubtitleExportFormat) {
        val linesSnapshot = state.lines
        if (linesSnapshot.isEmpty()) {
            android.widget.Toast.makeText(ctx, "当前没有可导出的字幕", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val base = state.title.ifBlank { "subtitles" }
            .replace(Regex("[\\\\/:*?\\\"<>|]"), "_")
            .take(48)
            .ifBlank { "subtitles" }
        subtitleExportScope.launch {
            // Large subtitle files can contain tens of thousands of cues. Build export text off
            // the UI thread so scrolling and playback controls remain responsive.
            val content = withContext(Dispatchers.Default) {
                when (format) {
                    SubtitleExportFormat.CHINESE_SRT -> SubtitleParsers.toSrt(linesSnapshot)
                    SubtitleExportFormat.BILINGUAL_SRT -> SubtitleParsers.toSrt(linesSnapshot, bilingual = true)
                    SubtitleExportFormat.BILINGUAL_VTT -> SubtitleParsers.toBilingualVtt(linesSnapshot)
                }
            }
            if (content.isBlank()) {
                android.widget.Toast.makeText(ctx, "没有有效字幕可导出", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingSubtitleExportContent = content
            when (format) {
                SubtitleExportFormat.CHINESE_SRT -> subtitleSrtSaver.launch(base + "_zh.srt")
                SubtitleExportFormat.BILINGUAL_SRT -> subtitleBilingualSrtSaver.launch(base + "_bilingual.srt")
                SubtitleExportFormat.BILINGUAL_VTT -> subtitleBilingualVttSaver.launch(base + "_bilingual.vtt")
            }
        }
    }

    fun requestOriginalSubtitleExport() {
        val linesSnapshot = state.lines.mapIndexed { index, line ->
            line.copy(index = index, zh = null)
        }
        if (linesSnapshot.isEmpty()) {
            android.widget.Toast.makeText(ctx, "当前还没有可导出的原文字幕，请等待转写阶段完成。", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val base = state.title.ifBlank { "subtitles" }
            .replace(Regex("[\\\\/:*?\\\"<>|]"), "_")
            .take(48)
            .ifBlank { "subtitles" }
        subtitleExportScope.launch {
            val content = withContext(Dispatchers.Default) {
                // Drop any partial/automatic Chinese translation and export the original text only.
                SubtitleParsers.toSrt(linesSnapshot)
            }
            if (content.isBlank()) {
                android.widget.Toast.makeText(ctx, "没有有效原文字幕可导出", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingSubtitleExportContent = content
            subtitleSrtSaver.launch(base + "_original.srt")
        }
    }

    fun copySubtitles() {
        val linesSnapshot = state.lines
        if (linesSnapshot.isEmpty()) {
            android.widget.Toast.makeText(ctx, "当前没有可复制的字幕", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        subtitleExportScope.launch {
            val text = withContext(Dispatchers.Default) {
                // Keep copy and file export consistent: discard malformed time windows, sort
                // chronologically, and emit Chinese first with a distinct source row underneath.
                SubtitleParsers.toPlainText(linesSnapshot, bilingual = true)
            }
            if (text.isNotEmpty()) {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("subtitles", text))
                android.widget.Toast.makeText(ctx, "字幕已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val dubbingPreparationIncomplete = VideoProcessingFlowPolicy.isDubbingPreparationIncomplete(
        dubbingOn = state.dubbingOn,
        totalLines = state.totalLines,
        preparedCount = state.preparedCount,
    )
    // The service gives the first usable cue a short startup window; once playback starts,
    // later cues can continue generating PCM in the background for Kokoro, Piper and System TTS.
    val progressiveTtsPlayback = state.ttsEngineName.isNotBlank()
    // A failed YouTube seek may trigger one bounded stream-refresh attempt. Keep the player
    // visible during those two explicitly-labelled recovery steps instead of showing the parser.
    val playbackRecoveryInProgress = state.key == requestKey && (
        state.progress.startsWith("YouTube 视频流加载失败，正在刷新播放地址") ||
            state.progress.startsWith("正在尝试兼容清晰度 ")
        )
    val showProcessingPage = VideoProcessingFlowPolicy.shouldShowProcessingPage(
        phase = state.phase,
        waitingForTranscriptionEngine = state.waitingForTranscriptionEngine,
        waitingForDubbingEngine = state.waitingForDubbingEngine,
        playerIsPlaying = state.isPlaying,
        progressiveTtsPlayback = progressiveTtsPlayback,
        dubbingPreparationIncomplete = dubbingPreparationIncomplete,
        playbackHasStarted = playbackHasStarted,
        playbackRecoveryInProgress = playbackRecoveryInProgress,
    )

    Box(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
    ) {
        if (showProcessingPage) {
            VideoParsingPage(
                state = state,
                onBack = { exitFloating(); PlaybackService.instance?.saveProgress(); onBack() },
                onRetry = { retryCurrentTask() },
                onTogglePause = { PlaybackService.instance?.toggleProcessingPause() },
                onCancel = { PlaybackService.instance?.cancelProcessing() },
                onImportSubtitles = { subPicker.launch(arrayOf("*/*")) },
                onImportTranslatedSubtitles = { translatedSubPicker.launch(arrayOf("*/*")) },
                onEnableManualTranslation = { PlaybackService.instance?.enableManualSubtitleTranslation() },
                onCancelManualTranslation = { PlaybackService.instance?.cancelManualSubtitleTranslation() },
                onExportOriginalSubtitle = { requestOriginalSubtitleExport() },
                onImportManualTranslatedSubtitle = { manualTranslatedSubPicker.launch(arrayOf("*/*")) },
                onDownloadSubtitle = { track -> requestSubtitleDownload(track) },
                onGoSettings = onGoSettings,
            )
        } else {
        Column(Modifier.fillMaxSize()) {
            // ===== 顶部栏（保留原导航栏外观与位置） =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(Color(0xFFF2F6FC))
                    .padding(horizontal = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        exitFloating()
                        PlaybackService.instance?.saveProgress()
                        onBack()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回主页",
                        tint = OnBg,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "视频播放",
                    color = OnBg,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(
                        onClick = { advancedMenuExpanded = true },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SurfaceDark)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "播放设置",
                            tint = OnBg,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = advancedMenuExpanded,
                        onDismissRequest = { advancedMenuExpanded = false },
                        modifier = Modifier.width(272.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "播放工具",
                                color = OnBg,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Divider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = OutlineSoft,
                            thickness = 1.dp,
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "原声音量",
                                    color = OnBg,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (state.originalVolume <= 0f) "关闭" else "${(state.originalVolume * 100f).roundToInt()}%",
                                    color = if (state.originalVolume <= 0f) SubText else BrandBright,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Slider(
                                value = state.originalVolume.coerceIn(0f, 1f),
                                onValueChange = { PlaybackService.instance?.setOriginalVolume(it, persist = false) },
                                onValueChangeFinished = { PlaybackService.instance?.finishOriginalVolumeAdjustment() },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "中文配音音量",
                                    color = OnBg,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (state.dubbingVolume <= 0f) "关闭" else "${(state.dubbingVolume * 100f).roundToInt()}%",
                                    color = if (state.dubbingVolume <= 0f) SubText else BrandBright,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Slider(
                                value = state.dubbingVolume.coerceIn(0f, 1f),
                                onValueChange = { PlaybackService.instance?.setDubbingVolume(it, persist = false) },
                                onValueChangeFinished = { PlaybackService.instance?.finishDubbingVolumeAdjustment() },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Divider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = OutlineSoft,
                            thickness = 1.dp,
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Headphones,
                            title = if (state.audioOnly) "恢复视频画面" else "仅听模式",
                            selected = state.audioOnly,
                            onClick = {
                                advancedMenuExpanded = false
                                PlaybackService.instance?.toggleAudioOnly()
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Face,
                            title = if (isSpeakerBusy) "正在识别人物…" else "识别并区分人物",
                            enabled = state.lines.isNotEmpty() && !isSpeakerBusy && voiceSelectionEnabled,
                            onClick = {
                                advancedMenuExpanded = false
                                PlaybackService.instance?.autoAssignSpeakers()
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Face,
                            title = "人物默认音色",
                            enabled = voiceSelectionEnabled && !isSpeakerBusy &&
                                state.currentLine in state.lines.indices,
                            onClick = {
                                advancedMenuExpanded = false
                                personTargetIndex = state.currentLine
                                val currentVoice = state.lines.getOrNull(personTargetIndex)?.speakerId ?: -1
                                selectedPersonVoiceId = currentVoice.takeIf { value ->
                                    voiceOptions.any { it.first == value }
                                } ?: -1
                                personVoiceMenuExpanded = false
                                showPersonVoiceDialog = true
                            },
                        )
                        Divider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = OutlineSoft,
                            thickness = 1.dp,
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Subtitles,
                            title = "导入字幕文件",
                            onClick = {
                                advancedMenuExpanded = false
                                subPicker.launch(arrayOf("*/*"))
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Subtitles,
                            title = "上传已翻译字幕并配音",
                            onClick = {
                                advancedMenuExpanded = false
                                translatedSubPicker.launch(arrayOf("*/*"))
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Subtitles,
                            title = "下载原字幕文件",
                            enabled = state.availableSubtitleTracks.isNotEmpty(),
                            onClick = {
                                advancedMenuExpanded = false
                                showSubtitleDownloadDialog = true
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.ContentCopy,
                            title = "复制双语字幕",
                            enabled = state.lines.isNotEmpty(),
                            onClick = {
                                advancedMenuExpanded = false
                                copySubtitles()
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Subtitles,
                            title = "导出中文字幕（SRT）",
                            enabled = state.lines.isNotEmpty(),
                            onClick = {
                                advancedMenuExpanded = false
                                requestSubtitleExport(SubtitleExportFormat.CHINESE_SRT)
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Subtitles,
                            title = "导出双语字幕（VTT）",
                            enabled = state.lines.isNotEmpty(),
                            onClick = {
                                advancedMenuExpanded = false
                                requestSubtitleExport(SubtitleExportFormat.BILINGUAL_VTT)
                            },
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Subtitles,
                            title = "导出双语字幕（SRT）",
                            enabled = state.lines.isNotEmpty(),
                            onClick = {
                                advancedMenuExpanded = false
                                requestSubtitleExport(SubtitleExportFormat.BILINGUAL_SRT)
                            },
                        )
                        Divider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = OutlineSoft,
                            thickness = 1.dp,
                        )
                        PlaybackMenuItem(
                            icon = Icons.Filled.Settings,
                            title = "翻译与配音设置",
                            onClick = {
                                advancedMenuExpanded = false
                                onGoSettings()
                            },
                        )
                    }
                }
            }
            }

            // ===== 视频画面（常驻容器，浮窗时 tv 被摘走；竖屏视频自适应比例） =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.videoAspect)
                    .background(Color(0xFF172033))
            ) {
                AndroidView(
                    factory = {
                        // While floating, the TextureView belongs to the overlay host.
                        // Otherwise the player may have just returned from the processing page,
                        // where this remembered frame can still contain tv.
                        if (!isFloating) moveTextureViewTo(frame)
                        attachSurfaceWhenReady()
                        frame
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.phase == Phase.READY || state.phase == Phase.SYNTHESIZING) {
                    VideoOverlayControls(
                        state = state,
                        isFloating = isFloating,
                        onStartFloat = { startFloatingWindow() },
                        onExitFloat = { exitFloating() },
                    )
                }
                // A compatible history item opens this player immediately. While its media session
                // is being restored, show a compact loading veil instead of the five-stage pipeline
                // card. Once its own state is published, the player and subtitles become interactive.
                val phase = state.phase
                val restoringHistorySession = showPlayerWhileRestoringHistory && (
                    state.key != requestKey ||
                        (phase != Phase.READY && phase != Phase.SYNTHESIZING && phase != Phase.ERROR)
                    )
                if (restoringHistorySession) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xD9F4F8FF)),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = BrandBright,
                                strokeWidth = 2.5.dp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("正在恢复历史视频…", color = OnBg, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "正在载入视频与字幕配音资源",
                                color = SubText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else if (phase != Phase.READY && phase != Phase.SYNTHESIZING) {
                    Box(Modifier.fillMaxSize().background(Color(0xD9F4F8FF)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            if (phase == Phase.ERROR) {
                                Text("加载失败", color = OnBg, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(3.dp))
                                Text(state.error ?: "", color = SubText, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { retryCurrentTask() }) {
                                    Text("重试", color = BrandBright)
                                }
                            } else {
                                ProcessingProgressCard(state = state)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 模型/配音问题提示可以单独关闭；关闭只隐藏提示，不会改变播放状态。
            (state.noModelHint?.takeIf { state.key == requestKey && it != dismissedNoModelHint })?.let { hint ->
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp)),
                    color = Color(0xFFFFF8E1),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 10.dp, end = 42.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("⚠️", color = WarnYellow)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                hint,
                                color = Color(0xFF5D4037),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "去设置 →",
                                color = AccentBlue,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable(onClick = onGoSettings),
                            )
                        }
                        IconButton(
                            onClick = { dismissedNoModelHint = hint },
                            modifier = Modifier.align(Alignment.TopEnd).size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭问题提示",
                                tint = Color(0xFF7A6A45),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
            }

            // 点了播放却被拦住（配音/转写引擎没就绪、配音出错等）：显示真实原因，不再是"点了没反应"。
            (state.playBlockedHint?.takeIf { state.key == requestKey && !state.isPlaying })?.let { blockedHint ->
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp)),
                    color = Color(0xFFFFF8E1),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 10.dp, end = 42.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                                Text("⚠️", color = WarnYellow)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    blockedHint,
                                    color = Color(0xFF5D4037),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                if (state.playBlockedOffersOriginalOnly) {
                                    Text(
                                        "仅播放原声",
                                        color = AccentBlue,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.clickable {
                                            PlaybackService.instance?.playOriginalOnly()
                                        },
                                    )
                                }
                                Text(
                                    "去设置 →",
                                    color = AccentBlue,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clickable(onClick = onGoSettings),
                                )
                            }
                        }
                        IconButton(
                            onClick = { PlaybackService.instance?.dismissPlayBlockedHint() },
                            modifier = Modifier.align(Alignment.TopEnd).size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭提示",
                                tint = Color(0xFF7A6A45),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
            }

            // 当前这句的中文配音还没合成好：红字提示，这句一合成好（或字幕换到别的句子）就立刻消失。
            // 判定来自配音引擎的逐句就绪状态，不用计时。
            if (state.key == requestKey && state.dubbingOn && state.dubbingCuePending &&
                (state.phase == Phase.READY || state.phase == Phase.SYNTHESIZING)
            ) {
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp)),
                    color = Color(0xFFFFEBEE),
                ) {
                    Text(
                        "配音合成未就绪，暂播原声" +
                            if (state.totalLines > 0) {
                                "（已备 ${state.preparedCount.coerceIn(0, state.totalLines)}/${state.totalLines}）"
                            } else "",
                        color = Color(0xFFD32F2F),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(5.dp))
            }

            if (state.key == requestKey) {
                CurrentSubtitleStrip(state = state)
            } else {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 28.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("正在载入历史视频字幕…", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(5.dp))

            if (state.key == requestKey) {
                PlaybackModeSelector(
                    state = state,
                    subtitlesVisible = subtitlesVisible,
                    onOriginal = { PlaybackService.instance?.toggleOriginalVolume() },
                    onDub = { PlaybackService.instance?.toggleDubbingVolume() },
                    onToggleSubtitles = { subtitlesVisible = !subtitlesVisible },
                )
            } else if (showPlayerWhileRestoringHistory) {
                Text("正在恢复播放设置…", color = SubText, style = MaterialTheme.typography.bodySmall)
            }

            // 配音/字幕轨的状态提示不再挤占播放控制栏与完整字幕列表之间的空间。
            Spacer(Modifier.height(6.dp))

            if (subtitlesVisible) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (state.key == requestKey) {
                        SubtitleList(
                            state = state,
                            svc = PlaybackService.instance,
                            listState = listState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("正在载入字幕…", color = SubText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("字幕显示已关闭", color = SubText, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        }
    }

    if (showPersonVoiceDialog) {
        val targetLine = state.lines.getOrNull(personTargetIndex)
        val currentVoiceName = voiceOptions.firstOrNull { it.first == selectedPersonVoiceId }?.second
            ?: if (selectedTtsEngine == "system") "系统默认音色" else "默认音色"
        AlertDialog(
            onDismissRequest = {
                showPersonVoiceDialog = false
                personVoiceMenuExpanded = false
            },
            title = { Text("人物默认音色") },
            text = {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        targetLine?.let { SubtitleParsers.preferredTranslationText(it) ?: it.text }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "当前字幕：$it" }
                            ?: "当前没有可用字幕行，请先播放到有字幕的位置。",
                        color = OnBg,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        Surface(
                            onClick = { personVoiceMenuExpanded = true },
                            enabled = voiceSelectionEnabled,
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFF2F6FC),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Face, contentDescription = null, tint = BrandBright)
                                Text(currentVoiceName, color = OnBg, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.MoreVert, contentDescription = "选择音色", tint = SubText)
                            }
                        }
                        DropdownMenu(
                            expanded = personVoiceMenuExpanded,
                            onDismissRequest = { personVoiceMenuExpanded = false },
                        ) {
                            voiceOptions.forEach { (voiceId, voiceName) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (selectedPersonVoiceId == voiceId) "$voiceName（已选择）"
                                            else voiceName
                                        )
                                    },
                                    onClick = {
                                        selectedPersonVoiceId = voiceId
                                        personVoiceMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        "“默认音色”使用所选配音引擎的默认中文声音。识别并区分人物后，可将当前选择应用到该人物的全部字幕。",
                        color = SubText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = targetLine != null && voiceSelectionEnabled,
                        onClick = {
                            showPersonVoiceDialog = false
                            personVoiceMenuExpanded = false
                            PlaybackService.instance?.setLineSpeaker(
                                personTargetIndex,
                                selectedPersonVoiceId,
                                applyToPerson = true,
                            )
                        },
                    ) { Text("设为此人物默认") }
                    TextButton(
                        enabled = targetLine != null && voiceSelectionEnabled,
                        onClick = {
                            showPersonVoiceDialog = false
                            personVoiceMenuExpanded = false
                            PlaybackService.instance?.setLineSpeaker(personTargetIndex, selectedPersonVoiceId)
                        },
                    ) { Text("仅当前句") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPersonVoiceDialog = false
                        personVoiceMenuExpanded = false
                    }
                ) { Text("取消") }
            },
        )
    }

    if (showSubtitleDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showSubtitleDownloadDialog = false },
            title = { Text("选择要下载的字幕") },
            text = {
                if (state.availableSubtitleTracks.isEmpty()) {
                    Text("当前视频没有可用的字幕轨。")
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.availableSubtitleTracks.forEach { track ->
                            TextButton(
                                onClick = {
                                    showSubtitleDownloadDialog = false
                                    requestSubtitleDownload(track)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    track.languageTag + " · " + (if (track.isAutoGenerated) "自动生成" else "人工字幕"),
                                    color = OnBg,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDownloadDialog = false }) { Text("关闭") }
            },
        )
    }

    // 自动滚动始终跟随视频时间轴。配音音轨异步启动不能延迟字幕滚动。
    // 用 rememberUpdatedState 让长生命周期协程始终读取最新播放行，不会闭包住旧 state。
    val effectiveScrollLine = state.currentLine
    val autoScrollTarget = rememberUpdatedState(effectiveScrollLine to state.lines.size)
    LaunchedEffect(state.key, state.phase, subtitlesVisible) {
        // The list is intentionally absent on the parsing screen and when subtitles are hidden.
        // Do not wait for a LazyColumn viewport that is not composed.
        if (state.phase != Phase.READY || !subtitlesVisible) return@LaunchedEffect
        snapshotFlow { autoScrollTarget.value }
            .distinctUntilChanged()
            .collectLatest { (target, lineCount) ->
                if (target !in 0 until lineCount) return@collectLatest

                // 等待 LazyColumn 完成测量；避免初次 viewport 为 0 时错过第一条字幕。
                listState.layoutInfo.let { info ->
                    if (info.viewportSize.height <= 0 || info.totalItemsCount <= target) {
                        snapshotFlow {
                            listState.layoutInfo.viewportSize.height to listState.layoutInfo.totalItemsCount
                        }.first { (height, itemCount) -> height > 0 && itemCount > target }
                    }
                }

                val info = listState.layoutInfo
                val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
                val topGuard = info.viewportStartOffset + (viewportHeight * 0.35f).toInt()
                val bottomGuard = info.viewportStartOffset + (viewportHeight * 0.65f).toInt()
                val item = info.visibleItemsInfo.firstOrNull { it.index == target }
                val itemBottom = item?.let { it.offset + it.size } ?: Int.MIN_VALUE

                // 让正在配音的字幕稳定停留在视口中央附近，避免观众反复追着字幕找。
                if (item == null || item.offset < topGuard || itemBottom > bottomGuard) {
                    listState.animateScrollToItem(
                        target,
                        scrollOffset = -(viewportHeight * 0.43f).toInt(),
                    )
                }
            }
    }
}


@Composable
private fun VideoParsingPage(
    state: PlayerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onImportSubtitles: () -> Unit,
    onImportTranslatedSubtitles: () -> Unit,
    onEnableManualTranslation: () -> Unit,
    onCancelManualTranslation: () -> Unit,
    onExportOriginalSubtitle: () -> Unit,
    onImportManualTranslatedSubtitle: () -> Unit,
    onDownloadSubtitle: (SubtitleTrackOption) -> Unit,
    onGoSettings: () -> Unit,
) {
    val source = VideoProcessingFlowPolicy.sourceKind(state.key)
    val flow = state.processingFlow
    val steps = VideoProcessingFlowPolicy.steps(source, flow)
    // Optimistic UI state makes the external-translation card react to the menu tap immediately,
    // while still syncing with the service as the pipeline advances or the mode is cancelled.
    var externalTranslationCardVisible by remember(state.key) {
        mutableStateOf(state.manualTranslationModeEnabled || state.manualTranslationWaiting)
    }
    LaunchedEffect(state.key, state.manualTranslationModeEnabled, state.manualTranslationWaiting) {
        externalTranslationCardVisible =
            state.manualTranslationModeEnabled || state.manualTranslationWaiting
    }
    val context = LocalContext.current
    val translationPrefs = remember(context) {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    }
    val selectedTranslationId = translationPrefs.getLong("active_api_id", EngineFactory.ID_GOOGLE)
    val isLocalTranslation = EngineFactory.isLocalTranslationEngine(selectedTranslationId)
    val localTranslationName = EngineFactory.localTranslationEngineName(selectedTranslationId).orEmpty()
    val configuredSourceLanguage = translationPrefs.getString("translation_source_language", "auto")
        .orEmpty().ifBlank { "auto" }
    val detectedSourceLanguage = state.sourceLang.trim().takeIf { code ->
        val normalized = code.lowercase(java.util.Locale.ROOT).replace('_', '-')
        normalized.isNotBlank() &&
            normalized.substringBefore('-') !in setOf("auto", "und", "unknown", "undefined")
    }
    val sourceLanguageForDisplay = detectedSourceLanguage ?: configuredSourceLanguage
    val normalizedSourceLanguageForDisplay = sourceLanguageForDisplay
        .trim().lowercase(java.util.Locale.ROOT).replace('_', '-')
    val sourceLanguageDisplay = when {
        normalizedSourceLanguageForDisplay.isBlank() ||
            normalizedSourceLanguageForDisplay.substringBefore('-') in
                setOf("auto", "und", "unknown", "undefined") ->
            if (selectedTranslationId == EngineFactory.ID_LOCAL_OPUS) {
                "自动识别（OPUS-MT 仅支持英语）"
            } else {
                "自动识别"
            }
        else -> LangMap.display(sourceLanguageForDisplay)
    }
    val screenConfig = androidx.compose.ui.platform.LocalConfiguration.current
    // Keep the full card design on larger screens; use a slightly denser rhythm on short phones.
    val compactLayout = screenConfig.screenHeightDp < 740 || screenConfig.screenWidthDp < 360
    var processingSettingsOpen by remember { mutableStateOf(false) }

    val isCancelled = state.phase == Phase.ERROR && state.error.orEmpty().contains("已取消处理")
    val isError = state.phase == Phase.ERROR && !isCancelled
    val waitingForTranscriptionEngine = state.waitingForTranscriptionEngine && state.phase != Phase.ERROR
    val waitingForDubbingEngine = state.waitingForDubbingEngine && state.phase != Phase.ERROR
    val dubbingPreparationIncomplete = VideoProcessingFlowPolicy.isDubbingPreparationIncomplete(
        dubbingOn = state.dubbingOn,
        totalLines = state.totalLines,
        preparedCount = state.preparedCount,
    )
    val dubbingPreparationPendingOnReady = state.phase == Phase.READY && dubbingPreparationIncomplete
    val displayPhase = if (waitingForDubbingEngine || dubbingPreparationPendingOnReady) {
        Phase.SYNTHESIZING
    } else {
        state.phase
    }

    val currentStage = VideoProcessingFlowPolicy.stageIndex(
        source = source,
        flow = flow,
        phase = displayPhase,
        failedPhase = state.failedPhase,
        progress = if (state.phase == Phase.ERROR) state.failedProgress else state.progress,
        waitingForTranscriptionEngine = waitingForTranscriptionEngine,
    ).coerceIn(0, (steps.size - 1).coerceAtLeast(0))

    val fraction: Float? = when (state.phase) {
        Phase.READY -> when {
            waitingForTranscriptionEngine || waitingForDubbingEngine -> null
            dubbingPreparationIncomplete && state.totalLines > 0 ->
                (state.preparedCount.toFloat() / state.totalLines).coerceIn(0f, 1f)
            VideoProcessingFlowPolicy.isCompleted(
                state.phase,
                waitingForTranscriptionEngine,
                waitingForDubbingEngine,
                state.dubbingHasErrors,
            ) -> 1f
            else -> null
        }
        Phase.SYNTHESIZING -> if (state.totalLines > 0) {
            (state.preparedCount.toFloat() / state.totalLines).coerceIn(0f, 1f)
        } else null
        else -> progressRatio(state.progress) ?: progressPercent(state.progress)
    }
    val complete = VideoProcessingFlowPolicy.isCompleted(
        state.phase,
        waitingForTranscriptionEngine,
        waitingForDubbingEngine,
        state.dubbingHasErrors,
    ) && !dubbingPreparationPendingOnReady
    // Keep pause/cancel controls visible until active, waiting, or paused processing has finished.
    val busy = !complete && !isError && !isCancelled

    val mainTitle = when {
        isCancelled -> "处理已取消"
        waitingForTranscriptionEngine -> "等待转写引擎就绪"
        waitingForDubbingEngine -> "等待配音引擎就绪"
        dubbingPreparationPendingOnReady && state.dubbingHasErrors ->
            "正在准备配音，失败字幕已恢复原声"
        dubbingPreparationPendingOnReady -> "正在按时间轴生成中文配音"
        state.dubbingHasErrors -> "部分配音失败，相关字幕已恢复原声"
        else -> VideoProcessingFlowPolicy.mainTitle(
            source = source,
            phase = displayPhase,
            flow = flow,
            isPaused = state.isProcessingPaused,
            waitingForTranscriptionEngine = waitingForTranscriptionEngine,
        )
    }
    val detail = when {
        isCancelled -> "任务已停止，可以重新开始处理"
        isError -> state.error?.takeIf { it.isNotBlank() } ?: "当前阶段未能完成，请重试"
        state.noModelHint != null && state.phase != Phase.ERROR -> state.noModelHint.orEmpty()
        state.isProcessingPaused ->
            "任务已暂停 · " + state.progress.ifBlank { "点击继续处理以恢复当前任务" }
        state.progress.isNotBlank() -> state.progress
        dubbingPreparationPendingOnReady ->
            "已准备 ${state.preparedCount.coerceIn(0, state.totalLines)}/${state.totalLines} 行配音，剩余字幕仍在处理"
        else -> VideoProcessingFlowPolicy.defaultDetail(source, flow, state.phase, state.error)
    }

    val sourceLabel = when (source) {
        VideoSourceKind.YOUTUBE -> "YouTube 来源"
        VideoSourceKind.WEB -> "网页视频来源"
        VideoSourceKind.LOCAL -> "本地视频来源"
        VideoSourceKind.UNKNOWN -> "视频处理"
    }
    val subtitleHeadline = if (source == VideoSourceKind.YOUTUBE) "可用字幕" else "字幕生成状态"
    val subtitleState = when {
        isError -> if (source == VideoSourceKind.YOUTUBE) "未完成" else "需重试"
        complete -> "已就绪"
        waitingForTranscriptionEngine -> "等待转写"
        state.availableSubtitleTracks.isNotEmpty() -> "已发现 ${state.availableSubtitleTracks.size} 条"
        source == VideoSourceKind.YOUTUBE -> "查找中"
        else -> "自动生成"
    }
    // YouTube、网页视频与本地视频始终展示完整处理阶段，不再折叠后续步骤。
    val visibleStages = steps.indices.toList()
    val animatedFraction by animateFloatAsState(
        targetValue = fraction?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "processingProgress",
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFF))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        // Compact navigation header; keep only the back and processing-actions controls.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 7.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE8EDF5), RoundedCornerShape(15.dp))
                    .clickable(onClick = onBack),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color(0xFF273953),
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { processingSettingsOpen = true },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多处理操作",
                        tint = Color(0xFF43546D),
                        modifier = Modifier.size(22.dp),
                    )
                }
                DropdownMenu(
                    expanded = processingSettingsOpen,
                    onDismissRequest = { processingSettingsOpen = false },
                    modifier = Modifier.width(276.dp),
                ) {
                    Text(
                        "字幕操作",
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                        color = Color(0xFF7A8495),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (externalTranslationCardVisible) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "取消外部翻译",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Text(
                                    "✕",
                                    color = Color(0xFFE53935),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            onClick = {
                                processingSettingsOpen = false
                                externalTranslationCardVisible = false
                                onCancelManualTranslation()
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "启用外部翻译模式",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Subtitles, contentDescription = null, tint = Color(0xFF3268F5))
                            },
                            enabled = state.phase in setOf(
                                Phase.PARSING,
                                Phase.DOWNLOADING_AUDIO,
                                Phase.TRANSCRIBING,
                                Phase.TRANSLATING,
                            ),
                            onClick = {
                                processingSettingsOpen = false
                                externalTranslationCardVisible = true
                                onEnableManualTranslation()
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("导出原文字幕（SRT）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, tint = Color(0xFF3268F5))
                        },
                        enabled = state.lines.isNotEmpty(),
                        onClick = {
                            processingSettingsOpen = false
                            onExportOriginalSubtitle()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("导入译文并继续处理", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, tint = Color(0xFF067647))
                        },
                        enabled = state.manualTranslationModeEnabled && state.manualTranslationWaiting,
                        onClick = {
                            processingSettingsOpen = false
                            onImportManualTranslatedSubtitle()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("导入字幕并自动翻译", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, tint = Color(0xFF3268F5))
                        },
                        onClick = {
                            processingSettingsOpen = false
                            onImportSubtitles()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("导入已翻译字幕并配音", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, tint = Color(0xFF3268F5))
                        },
                        onClick = {
                            processingSettingsOpen = false
                            onImportTranslatedSubtitles()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color(0xFFE9EDF3),
                    )
                    Text(
                        "应用设置",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        color = Color(0xFF7A8495),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    DropdownMenuItem(
                        text = { Text("翻译与配音设置", maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Filled.Settings, contentDescription = null, tint = Color(0xFF3268F5))
                        },
                        onClick = {
                            processingSettingsOpen = false
                            onGoSettings()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    )
                }
            }
        }

        // Keep the external-translation waiting card visible only while the mode itself is active.
        // When the user cancels, clearing manualTranslationModeEnabled closes this card immediately,
        // even if a stale waiting flag is briefly observed during pipeline resumption.
        if (externalTranslationCardVisible) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (state.manualTranslationWaiting) "等待外部译文" else "外部翻译已启用",
                        color = Color(0xFF20324B),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    // 按钮灰着时说明原因：原文字幕还没生成，或正在结束手头的翻译请求（马上可用）。
                    if (!state.manualTranslationWaiting) {
                        Text(
                            if (state.lines.isEmpty()) {
                                "原文字幕还在生成，生成后即可导出原文、导入译文。"
                            } else {
                                "正在停止当前翻译，马上就可以导入译文。"
                            },
                            color = Color(0xFF5B6B84),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = onExportOriginalSubtitle,
                            // 原文字幕一旦生成就能导出，不必等进入"等待外部译文"状态。
                            enabled = state.lines.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("导出原文")
                        }
                        Button(
                            onClick = onImportManualTranslatedSubtitle,
                            enabled = state.manualTranslationWaiting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("导入译文")
                        }
                    }
                }
            }
        }

        // Hero card: source, task title, progress and primary actions.
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(
                    6.dp,
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = 9.dp,
                        bottomEnd = 9.dp,
                    ),
                    clip = false,
                )
                .clip(
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = 9.dp,
                        bottomEnd = 9.dp,
                    ),
                )
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFF1F7FF),
                            Color(0xFFFFFFFF),
                            Color(0xFFFAF8FF),
                        ),
                    ),
                )
                .border(
                    1.dp,
                    Color(0xFFE2EAFF),
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = 9.dp,
                        bottomEnd = 9.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = if (compactLayout) 10.dp else 12.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(
                        Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFE5F0FF), Color(0xFFF2F2FF)),
                                ),
                            ),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 49.dp, height = 43.dp)
                                .graphicsLayer { rotationZ = -7f }
                                .shadow(5.dp, RoundedCornerShape(13.dp))
                                .clip(RoundedCornerShape(13.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE7EEFF), RoundedCornerShape(13.dp)),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(width = 32.dp, height = 24.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        if (source == VideoSourceKind.YOUTUBE) Color(0xFFFF4A55)
                                        else Color(0xFF3268F5),
                                    ),
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Text(
                            "中",
                            color = Color(0xFF3268F5),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 2.dp, bottom = 1.dp)
                                .shadow(2.dp, RoundedCornerShape(7.dp))
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color.White)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                        Text(
                            "A↔文",
                            color = Color(0xFF4977E9),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 7.sp,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 1.dp, end = 1.dp)
                                .shadow(2.dp, RoundedCornerShape(6.dp))
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF3268F5)),
                            )
                            Text(
                                sourceLabel,
                                color = Color(0xFF536985),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                        Text(
                            mainTitle,
                            color = Color(0xFF17243B),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.35).sp,
                                lineHeight = 21.sp,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            detail,
                            color = Color(0xFF71819B),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        modifier = Modifier.padding(start = 1.dp).widthIn(max = 68.dp),
                    ) {
                        val stateColor = when {
                            isError || isCancelled -> Color(0xFFCB3E4D)
                            state.isProcessingPaused -> Color(0xFFA86B13)
                            complete -> Color(0xFF15996E)
                            else -> Color(0xFF3268E9)
                        }
                        val stateBg = when {
                            isError || isCancelled -> Color(0xFFFFF0F0)
                            state.isProcessingPaused -> Color(0xFFFFF6E7)
                            complete -> Color(0xFFE4F8EF)
                            else -> Color(0xFFE9F0FF)
                        }
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(stateBg)
                                .padding(horizontal = 7.dp, vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (busy && !state.isProcessingPaused) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    color = stateColor,
                                    strokeWidth = 1.7.dp,
                                )
                            } else if (complete) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = stateColor, modifier = Modifier.size(11.dp))
                            } else if (isError || isCancelled) {
                                Text("!", color = stateColor, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                when {
                                    isCancelled -> "已取消"
                                    isError -> "失败"
                                    state.isProcessingPaused -> "已暂停"
                                    complete -> "已完成"
                                    waitingForTranscriptionEngine -> "等待转写"
                                    waitingForDubbingEngine -> "等待配音"
                                    else -> "处理中"
                                },
                                color = stateColor,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (isLocalTranslation) {
                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF0F5FF))
                            .border(1.dp, Color(0xFFE0E9FF), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Subtitles,
                            contentDescription = null,
                            tint = Color(0xFF3268F5),
                            modifier = Modifier.size(18.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "本地翻译 · $localTranslationName",
                                color = Color(0xFF244A9B),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "语言方向：$sourceLanguageDisplay → 简体中文",
                                color = Color(0xFF617394),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(if (compactLayout) 8.dp else 10.dp))
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier.weight(1f).height(7.dp).clip(CircleShape),
                            color = Color(0xFF3268F5),
                            trackColor = Color(0xFFE8EDF6),
                        )
                    } else if (busy && !state.isProcessingPaused) {
                        LinearProgressIndicator(
                            modifier = Modifier.weight(1f).height(7.dp).clip(CircleShape),
                            color = Color(0xFF3268F5),
                            trackColor = Color(0xFFE8EDF6),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { 0f },
                            modifier = Modifier.weight(1f).height(7.dp).clip(CircleShape),
                            color = Color(0xFF3268F5),
                            trackColor = Color(0xFFE8EDF6),
                        )
                    }
                    Text(
                        if (complete) "${steps.size} / ${steps.size}" else "${(currentStage + 1).coerceAtMost(steps.size)} / ${steps.size}",
                        color = Color(0xFF6B7C98),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = if (compactLayout) 8.dp else 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = Color(0xFF8190A8),
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            when {
                                isCancelled -> "当前任务已停止"
                                isError -> "处理未能完成"
                                complete -> "视频、字幕与配音已就绪"
                                state.isProcessingPaused -> "已暂停 · 等待继续处理"
                                fraction != null -> "实际进度 ${(animatedFraction * 100).toInt()}%"
                                else -> "正在处理当前阶段…"
                            },
                            color = Color(0xFF7C8BA2),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (waitingForTranscriptionEngine || waitingForDubbingEngine) {
                        Text(
                            if (waitingForTranscriptionEngine) "等待转写引擎" else "等待配音引擎",
                            color = Color(0xFF6680AC),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        )
                    }
                }

                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        val pauseLabel = if (state.isProcessingPaused) "继续处理" else "暂停处理"
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (state.isProcessingPaused) Brush.linearGradient(listOf(Color(0xFF3268F5), Color(0xFF517EF8))) else Brush.linearGradient(listOf(Color.White, Color(0xFFF9FBFF))))
                                .border(1.dp, Color(0xFFCBDCFF), RoundedCornerShape(14.dp))
                                .clickable(onClick = onTogglePause),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    if (state.isProcessingPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = null,
                                    tint = if (state.isProcessingPaused) Color.White else Color(0xFF2862EB),
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    pauseLabel,
                                    color = if (state.isProcessingPaused) Color.White else Color(0xFF2862EB),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                )
                            }
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFFFF5F6))))
                                .border(1.dp, Color(0xFFFFD6DB), RoundedCornerShape(14.dp))
                                .clickable(onClick = onCancel),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("×", color = Color(0xFFD94554), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "取消处理",
                                    color = Color(0xFFD94554),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Stitched seam: a fine dashed thread visually joins the two cards.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(6.dp),
        ) {
            drawLine(
                color = Color(0xFFBCCBE5),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(3.dp.toPx(), 3.dp.toPx()),
                    0f,
                ),
            )
        }

        // Stage timeline — the lower card is rounded to meet the stitched upper card.
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(
                    6.dp,
                    RoundedCornerShape(
                        topStart = 9.dp,
                        topEnd = 9.dp,
                        bottomStart = 22.dp,
                        bottomEnd = 22.dp,
                    ),
                    clip = false,
                )
                .clip(
                    RoundedCornerShape(
                        topStart = 9.dp,
                        topEnd = 9.dp,
                        bottomStart = 22.dp,
                        bottomEnd = 22.dp,
                    ),
                )
                .background(Color.White)
                .border(
                    1.dp,
                    Color(0xFFE8EDF5),
                    RoundedCornerShape(
                        topStart = 9.dp,
                        topEnd = 9.dp,
                        bottomStart = 22.dp,
                        bottomEnd = 22.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = if (compactLayout) 9.dp else 12.dp),
        ) {
            Column {
                visibleStages.forEachIndexed { visibleIndex, index ->
                    val done = complete || index < currentStage
                    val active = !complete && !isError && !isCancelled && index == currentStage
                    val failed = isError && index == currentStage
                    val cancelled = isCancelled && index == currentStage
                    val paused = active && state.isProcessingPaused
                    val markerBg = when {
                        done -> Color(0xFFE8F8F1)
                        failed -> Color(0xFFFFE4E6)
                        cancelled -> Color(0xFFFFF6E7)
                        active -> Color(0xFFE5EEFF)
                        else -> Color(0xFFF0F3F8)
                    }
                    val markerColor = when {
                        done -> Color(0xFF18A879)
                        failed -> Color(0xFFD74655)
                        cancelled -> Color(0xFFA86B13)
                        active -> Color(0xFF3268F5)
                        else -> Color(0xFF9EACC0)
                    }
                    val rowBg = when {
                        done -> Brush.linearGradient(listOf(Color(0xFFF2FBF7), Color.White))
                        failed -> Brush.linearGradient(listOf(Color(0xFFFFF4F4), Color.White))
                        active -> Brush.linearGradient(listOf(Color(0xFFEFF5FF), Color(0xFFF8FAFF)))
                        else -> Brush.linearGradient(listOf(Color.White, Color(0xFFFCFDFF)))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (compactLayout) 2.dp else 4.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(rowBg)
                            .border(
                                width = if (active || failed) 1.dp else 0.dp,
                                color = if (active) Color(0xFFE4EDFF) else Color(0xFFFFD6DB),
                                shape = RoundedCornerShape(17.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = if (compactLayout) 6.dp else 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (compactLayout) 7.dp else 10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(if (compactLayout) 28.dp else 32.dp)
                                .clip(CircleShape)
                                .background(markerBg)
                                .border(1.dp, Color.White, CircleShape),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            when {
                                done -> Icon(Icons.Filled.Check, contentDescription = null, tint = markerColor, modifier = Modifier.size(if (compactLayout) 19.dp else 22.dp))
                                failed -> Text("!", color = markerColor, style = MaterialTheme.typography.titleMedium)
                                cancelled -> Text("Ⅱ", color = markerColor, style = MaterialTheme.typography.titleMedium)
                                active -> CircularProgressIndicator(
                                    modifier = Modifier.size(if (compactLayout) 19.dp else 22.dp),
                                    color = markerColor,
                                    strokeWidth = 2.dp,
                                )
                                else -> Box(Modifier.size(7.dp).clip(CircleShape).background(markerColor))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${index + 1}. ${steps[index]}",
                                color = when {
                                    failed -> Color(0xFFCB3E4D)
                                    active -> Color(0xFF265EE3)
                                    else -> Color(0xFF34455F)
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = if (compactLayout) 12.sp else 13.sp,
                                    fontWeight = if (active || done) FontWeight.Bold else FontWeight.SemiBold,
                                    lineHeight = if (compactLayout) 15.sp else 18.sp,
                                ),
                            )
                            Text(
                                when {
                                    done -> "已完成"
                                    failed -> "此阶段处理未成功"
                                    cancelled -> "处理已停止"
                                    paused -> "任务已暂停，等待继续"
                                    active && state.progress.isNotBlank() -> state.progress.take(72)
                                    active && waitingForTranscriptionEngine -> "等待转写引擎就绪"
                                    active && waitingForDubbingEngine -> "等待配音引擎就绪"
                                    active -> "正在处理当前阶段…"
                                    else -> "等待前序步骤完成"
                                },
                                color = when {
                                    done -> Color(0xFF159B71)
                                    failed -> Color(0xFFCB3E4D)
                                    active -> Color(0xFF8193B5)
                                    else -> Color(0xFF8D9BB0)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = if (compactLayout) 9.sp else 10.sp,
                                    lineHeight = if (compactLayout) 11.sp else 13.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val stageBadgeText = when {
                            done -> "已完成"
                            failed -> "失败"
                            cancelled -> "已取消"
                            paused -> "已暂停"
                            active -> if (fraction != null) "${(animatedFraction * 100).toInt()}%" else "进行中"
                            else -> "待处理"
                        }
                        val stageBadgeBg = when {
                            done -> Color(0xFFE2F7ED)
                            failed -> Color(0xFFFFE6E8)
                            active && !paused -> Color(0xFFDFEAFF)
                            paused || cancelled -> Color(0xFFFFF0D8)
                            else -> Color(0xFFF1F4F8)
                        }
                        val stageBadgeColor = when {
                            done -> Color(0xFF14996C)
                            failed -> Color(0xFFCB3C4C)
                            active && !paused -> Color(0xFF2862EA)
                            paused || cancelled -> Color(0xFFA86B13)
                            else -> Color(0xFF8794A8)
                        }
                        Text(
                            stageBadgeText,
                            color = stageBadgeColor,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(stageBadgeBg)
                                .padding(horizontal = if (compactLayout) 4.dp else 8.dp, vertical = if (compactLayout) 4.dp else 5.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = if (compactLayout) 9.sp else 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    }
                    if (visibleIndex < visibleStages.lastIndex) {
                        Box(
                            Modifier
                                .padding(start = 21.dp)
                                .width(2.dp)
                                .height(if (compactLayout) 5.dp else 7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE9EEF7)),
                        )
                    }
                }
                if (state.phase == Phase.ERROR) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isCancelled) Color(0xFFFFF7E9) else Color(0xFFFFF1F2))
                            .border(
                                1.dp,
                                if (isCancelled) Color(0xFFFFE4B5) else Color(0xFFFFD8DD),
                                RoundedCornerShape(14.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (isCancelled) "处理已取消" else "处理未能完成",
                                color = if (isCancelled) Color(0xFFA86B13) else Color(0xFFCB3E4D),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(
                                state.error.orEmpty().ifBlank { detail },
                                color = if (isCancelled) Color(0xFF8C6C3F) else Color(0xFF9F4D56),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth().height(43.dp),
                                shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF3268F5),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(if (isCancelled) "重新开始处理" else "重试当前处理", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                Icons.Filled.Subtitles,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(20.dp),
            )
            Text(
                subtitleHeadline,
                color = Color(0xFF34435A),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.weight(1f))
            Text(
                subtitleState,
                color = when {
                    isError -> Color(0xFFCB3E4D)
                    complete -> Color(0xFF15996E)
                    else -> Color(0xFF3268F5)
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        when {
                            isError -> Color(0xFFFFF0F0)
                            complete -> Color(0xFFE4F8EF)
                            else -> Color(0xFFEDF3FF)
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        if (state.availableSubtitleTracks.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE5EAF2), RoundedCornerShape(16.dp)),
            ) {
                state.availableSubtitleTracks.forEachIndexed { index, track ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            color = Color(0xFFEEF1F6),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color(0xFFEEF3FF)),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Subtitles,
                                contentDescription = null,
                                tint = Color(0xFF3268F5),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                track.languageTag,
                                color = Color(0xFF26354B),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (track.isAutoGenerated) "自动生成字幕" else "人工字幕",
                                color = Color(0xFF7A8799),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                maxLines = 1,
                            )
                        }
                        FilledTonalButton(
                            onClick = { onDownloadSubtitle(track) },
                            modifier = Modifier.height(36.dp),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 11.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFFEAF1FF),
                                contentColor = Color(0xFF3268F5),
                            ),
                        ) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("下载", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }


}

@Composable
private fun CurrentSubtitleStrip(state: PlayerUiState) {
    // The visible caption follows the video playhead. TTS/audio-track startup is asynchronous
    // and must not delay the first subtitle strip or make captions drift behind the video.
    val index = state.currentLine.takeIf { it in state.lines.indices } ?: -1
    val currentLine = state.lines.getOrNull(index)
    val captionOverride = state.captionOverride
    val currentText = remember(currentLine?.text, currentLine?.zh, captionOverride) {
        currentLine?.let { line ->
            (captionOverride?.takeIf { it.isNotBlank() }
                ?: SubtitleParsers.preferredTranslationText(line)
                    ?.takeIf { it.isNotBlank() }
                ?: line.text)
                .replace(Regex("\\s+"), " ")
                .trim()
        }.orEmpty()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Color(0xFFE8EFFF))
            .padding(horizontal = 8.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (currentText.isNotBlank()) {
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            val baseStyle = MaterialTheme.typography.bodyMedium.copy(
                color = BrandBright,
                fontWeight = FontWeight.Medium,
            )
            // Allow two readable lines. Longer cues scale down only as much as needed
            // to fit in this compact region, rather than being clipped with an ellipsis.
            val fontSizeSp = remember(currentText, widthPx, heightPx, baseStyle.fontSize) {
                var low = 8.5f
                var high = 15.5f
                var best = low
                repeat(16) {
                    val candidate = (low + high) / 2f
                    val measured = textMeasurer.measure(
                        text = currentText,
                        style = baseStyle.copy(
                            fontSize = candidate.sp,
                            lineHeight = (candidate * 1.22f).sp,
                        ),
                        softWrap = true,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                        constraints = Constraints(
                            maxWidth = widthPx,
                            maxHeight = heightPx,
                        ),
                    )
                    if (!measured.didOverflowWidth &&
                        !measured.didOverflowHeight &&
                        measured.lineCount <= 2
                    ) {
                        best = candidate
                        low = candidate
                    } else {
                        high = candidate
                    }
                }
                best
            }
            Text(
                text = currentText,
                color = BrandBright,
                style = baseStyle.copy(
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.22f).sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun PlaybackModeSelector(
    state: PlayerUiState,
    subtitlesVisible: Boolean,
    onOriginal: () -> Unit,
    onDub: () -> Unit,
    onToggleSubtitles: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        ModeButton(
            modifier = Modifier.weight(1f),
            title = "原声",
            glyph = "◖))",
            selected = state.originalVolume > 0f,
            onClick = onOriginal,
        )
        ModeButton(
            modifier = Modifier.weight(1f),
            title = "中文配音",
            glyph = "≋",
            selected = state.dubbingVolume > 0f,
            onClick = onDub,
        )
        ModeButton(
            modifier = Modifier.weight(1f),
            title = "字幕",
            glyph = "▤",
            selected = subtitlesVisible,
            onClick = onToggleSubtitles,
        )
    }
}

@Composable
private fun ModeButton(
    modifier: Modifier,
    title: String,
    glyph: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFE4EDFF) else Color.White,
        animationSpec = tween(210, easing = FastOutSlowInEasing),
        label = "playbackModeColor",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) BrandBright else OnBg,
        animationSpec = tween(210, easing = FastOutSlowInEasing),
        label = "playbackModeLabelColor",
    )
    val glyphScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "playbackModeGlyphScale",
    )
    Surface(
        modifier = modifier.height(60.dp),
        onClick = onClick,
        color = selectedColor,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            selectedColor,
        ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 5.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                glyph,
                color = BrandBright,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                modifier = Modifier.graphicsLayer {
                    scaleX = glyphScale
                    scaleY = glyphScale
                },
            )
            Text(title, color = labelColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatFloatingTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60L, seconds)
    }
}

@Composable
private fun FloatingIconControl(
    icon: ImageVector,
    description: String,
    touchSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(touchSize)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 右下角的缩放把手：按住拖动即可放大/缩小悬浮窗，左上角位置不变。 */
@Composable
private fun FloatingResizeHandle(
    modifier: Modifier,
    size: androidx.compose.ui.unit.Dp,
    onBegin: () -> Unit,
    onResize: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
) {
    Box(
        modifier = modifier
            // requiredSize 强制此手柄保持 22–28dp，避免被全屏父容器拉伸。
            .requiredSize(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 先把这次按下据为己有：外层的"拖动窗口/点击显隐按钮"手势看到已被消费就不会处理。
                    down.consume()
                    onDraggingChange(true)
                    // 以此刻手指的屏幕位置和窗口尺寸为锚点。
                    onBegin()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            onResize()
                            if (!change.pressed) break
                        }
                    } finally {
                        onDraggingChange(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
            // Use DrawScope's canvas size, not the outer Dp argument named "size".
            val w = this.size.width
            val h = this.size.height
            val stroke = 1.5.dp.toPx()
            val color = Color.White
            // 两条短斜线只在右下角手柄范围内绘制，不会延伸覆盖视频画面。
            drawLine(
                color = color,
                start = Offset(w * 0.92f, h * 0.28f),
                end = Offset(w * 0.28f, h * 0.92f),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(w * 0.92f, h * 0.62f),
                end = Offset(w * 0.62f, h * 0.92f),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun FloatingPlayerControls(
    onBeginWindowDrag: () -> Unit,
    onMoveWindow: () -> Unit,
    onResizeWindow: () -> Unit,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val service = PlaybackService.instance
    // Keep the overlay state inside the overlay's own composition. Sharing it with the
    // Activity composition caused stale visibility when the Activity was backgrounded.
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    // 拖动/缩放悬浮窗期间不能自动隐藏按钮层，否则界面重组会打断正在进行的手势。
    var windowDragging by remember { mutableStateOf(false) }
    fun onInteraction() {
        controlsVisible = true
        interactionRevision++
    }
    val playbackState by PlaybackService.ui.collectAsState()
    var positionMs by remember(playbackState.key) {
        mutableStateOf(service?.position() ?: 0L)
    }
    var previewPositionMs by remember(playbackState.key) { mutableStateOf(0L) }
    var scrubbing by remember(playbackState.key) { mutableStateOf(false) }
    val durationMs = (service?.duration() ?: 0L)
        .takeIf { it > 0L } ?: playbackState.durationMs.coerceAtLeast(0L)

    LaunchedEffect(playbackState.key) {
        while (true) {
            if (!scrubbing) {
                positionMs = (PlaybackService.instance?.position() ?: positionMs)
                    .coerceAtLeast(0L)
            }
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible, interactionRevision, scrubbing, windowDragging) {
        if (!controlsVisible || scrubbing || windowDragging) return@LaunchedEffect
        delay(3_000)
        controlsVisible = false
    }

    val visiblePosition = if (scrubbing) previewPositionMs else positionMs
    val fraction = if (durationMs > 0L) {
        (visiblePosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    // 手势协程不要随 controlsVisible 重启（重启会把正在进行的拖动打断），用最新值读取即可。
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val overlayGestureModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A control or the seek bar already claimed this touch; do not turn it into
            // a window gesture or a visibility toggle.
            if (down.isConsumed) return@awaitEachGesture

            val start = down.position
            var draggingWindow = false

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed && !draggingWindow) break

                    if (!draggingWindow &&
                        (kotlin.math.abs(change.position.x - start.x) > viewConfiguration.touchSlop ||
                            kotlin.math.abs(change.position.y - start.y) > viewConfiguration.touchSlop)
                    ) {
                        draggingWindow = true
                        windowDragging = true
                        // 以此刻手指的屏幕位置为锚点，窗口不会突然跳一下。
                        onBeginWindowDrag()
                    }
                    if (draggingWindow) {
                        // 手势坐标是相对悬浮窗自身的，窗口一动坐标系也跟着变，且系统落实窗口新位置有延迟，
                        // 用它算位移会叠加、来回跳。这里只触发"按手指的屏幕绝对位置重新定位"，
                        // 具体位置由 FloatingManager 用屏幕坐标算，同一个手指位置永远得到同一个窗口位置。
                        change.consume()
                        onMoveWindow()
                    }

                    if (!change.pressed) {
                        if (!draggingWindow && !change.isConsumed) {
                            controlsVisible = !latestControlsVisible
                            if (controlsVisible) interactionRevision++
                        }
                        break
                    }
                }
            } finally {
                if (draggingWindow) windowDragging = false
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        // 固定缩放手柄尺寸。背景手势独立放在按钮层下方，按钮点击与进度条拖动
        // 不再和窗口拖动/显隐手势争用同一触摸目标。
        val resizeHandleSize = if (maxWidth < 190.dp) 22.dp else 28.dp
        Box(
            modifier = Modifier.fillMaxSize().then(overlayGestureModifier),
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier.fillMaxSize(),
        ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val compact = maxWidth < 190.dp
            val topTouch = if (compact) 30.dp else 36.dp
            val topIcon = if (compact) 19.dp else 22.dp
            val sideTouch = if (compact) 34.dp else 42.dp
            val sideIcon = if (compact) 21.dp else 25.dp
            val centerTouch = if (compact) 38.dp else 48.dp
            val centerIcon = if (compact) 27.dp else 34.dp
            val controlsGap = if (compact) 5.dp else 16.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconControl(
                    icon = Icons.Filled.Fullscreen,
                    description = "返回应用播放界面",
                    touchSize = topTouch,
                    iconSize = topIcon,
                    onClick = {
                        onInteraction()
                        onFullscreen()
                    },
                )
                FloatingIconControl(
                    icon = Icons.Filled.Close,
                    description = "关闭悬浮窗口",
                    touchSize = topTouch,
                    iconSize = topIcon,
                    onClick = {
                        onInteraction()
                        onClose()
                    },
                )
            }

            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(controlsGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconControl(
                    icon = Icons.Filled.Replay10,
                    description = "后退 10 秒",
                    touchSize = sideTouch,
                    iconSize = sideIcon,
                    onClick = {
                        val current = (service?.position() ?: positionMs).coerceAtLeast(0L)
                        val target = (current - 10_000L).coerceAtLeast(0L)
                        service?.seek(target)
                        positionMs = target
                        onInteraction()
                    },
                )
                FloatingIconControl(
                    icon = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    description = if (playbackState.isPlaying) "暂停" else "播放",
                    touchSize = centerTouch,
                    iconSize = centerIcon,
                    onClick = {
                        if (playbackState.isPlaying) service?.pausePlayback()
                        else service?.playPlayback()
                        onInteraction()
                    },
                )
                FloatingIconControl(
                    icon = Icons.Filled.Forward10,
                    description = "快进 10 秒",
                    touchSize = sideTouch,
                    iconSize = sideIcon,
                    onClick = {
                        val current = (service?.position() ?: positionMs).coerceAtLeast(0L)
                        val target = if (durationMs > 0L) {
                            (current + 10_000L).coerceAtMost(durationMs)
                        } else {
                            current + 10_000L
                        }
                        service?.seek(target)
                        positionMs = target
                        onInteraction()
                    },
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    // 右下角留给缩放把手，进度条和时间不要压在上面。
                    .padding(end = resizeHandleSize + 2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatFloatingTime(visiblePosition),
                        color = Color.White,
                        fontSize = if (compact) 9.sp else 10.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = formatFloatingTime(durationMs),
                        color = Color.White,
                        fontSize = if (compact) 9.sp else 10.sp,
                        maxLines = 1,
                    )
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 12.dp else 14.dp)
                        .pointerInput(playbackState.key, durationMs) {
                            detectTapGestures { offset ->
                                if (durationMs > 0L && size.width > 0) {
                                    val target = (
                                        (offset.x / size.width.toFloat()).coerceIn(0f, 1f) *
                                            durationMs.toFloat()
                                        ).toLong().coerceIn(0L, durationMs)
                                    service?.seek(target)
                                    positionMs = target
                                    onInteraction()
                                }
                            }
                        }
                        .pointerInput(playbackState.key, durationMs) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    scrubbing = true
                                    if (durationMs > 0L && size.width > 0) {
                                        val fractionAtTouch =
                                            (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        previewPositionMs =
                                            (fractionAtTouch * durationMs.toFloat()).toLong()
                                                .coerceIn(0L, durationMs)
                                    }
                                    onInteraction()
                                },
                                onDragEnd = {
                                    if (durationMs > 0L) {
                                        service?.seek(previewPositionMs.coerceIn(0L, durationMs))
                                        positionMs = previewPositionMs.coerceIn(0L, durationMs)
                                    }
                                    scrubbing = false
                                    onInteraction()
                                },
                                onDragCancel = {
                                    scrubbing = false
                                    onInteraction()
                                },
                            ) { change, _ ->
                                change.consume()
                                if (durationMs > 0L && size.width > 0) {
                                    val fractionAtTouch =
                                        (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    previewPositionMs =
                                        (fractionAtTouch * durationMs.toFloat()).toLong()
                                            .coerceIn(0L, durationMs)
                                }
                            }
                        },
                ) {
                    val lineHeight = 2.dp.toPx()
                    val centerY = size.height / 2f
                    val corner = CornerRadius(lineHeight / 2f, lineHeight / 2f)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.42f),
                        topLeft = Offset(0f, centerY - lineHeight / 2f),
                        size = Size(size.width, lineHeight),
                        cornerRadius = corner,
                    )
                    val activeWidth = size.width * fraction
                    if (activeWidth > 0f) {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(0f, centerY - lineHeight / 2f),
                            size = Size(activeWidth, lineHeight),
                            cornerRadius = corner,
                        )
                    }
                }
            }
        }
        }
        // 缩放手柄是独立的最上层控件，按钮隐藏时仍保持可见并可拖动。
        FloatingResizeHandle(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 4.dp),
            size = resizeHandleSize,
            onBegin = onBeginWindowDrag,
            onResize = onResizeWindow,
            onDraggingChange = { windowDragging = it },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.VideoOverlayControls(
    state: PlayerUiState,
    isFloating: Boolean,
    onStartFloat: () -> Unit,
    onExitFloat: () -> Unit,
) {
    // Resolve the live service at each interaction; the screen may compose before Service.onCreate completes.
    var pos by remember(state.key) { mutableStateOf(0f) }
    var scrubbing by remember(state.key) { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var speedExpanded by remember { mutableStateOf(false) }
    var controlsVisible by remember(state.key) { mutableStateOf(true) }
    var speed by remember(state.key) { mutableStateOf(1.0f) }
    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    // Hide the seekbar and player tools after a short idle period, whether playing or paused.
    // Leave dropdown menus interactive; tapping the video toggles the controls back on.
    LaunchedEffect(state.key, state.isPlaying, controlsVisible, qualityExpanded, speedExpanded, scrubbing) {
        if (!controlsVisible || qualityExpanded || speedExpanded || scrubbing) return@LaunchedEffect
        delay(3_000)
        controlsVisible = false
    }

    // This transparent tap target sits behind the controls, so tapping a button or seeking
    // keeps its own gesture; a tap on the actual picture toggles the overlay.
    Box(
        Modifier.fillMaxSize().pointerInput(state.key, state.isPlaying, controlsVisible) {
            detectTapGestures {
                controlsVisible = if (controlsVisible) false else true
            }
        }
    )

    LaunchedEffect(state.key) {
        while (true) {
            if (!scrubbing) {
                val duration = PlaybackService.instance?.duration() ?: 0L
                val position = PlaybackService.instance?.position() ?: 0L
                pos = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }
            delay(300)
        }
    }

    // 转圈直接反映播放器的真实加载状态（BUFFERING），文字提示来自真实数据：
    // 网络请求失败的重试次数、已缓冲的时长；没有数据时不编造"网络慢"之类的结论。
    if (state.isBuffering) {
        var bufferedMs by remember(state.key) { mutableStateOf(0L) }
        // 每 0.5 秒一格，16 格 = 加载满 8 秒才开始显示详情，避免一闪而过的正常缓冲也弹提示。
        var loadingTicks by remember(state.key) { mutableStateOf(0) }
        LaunchedEffect(state.key) {
            while (true) {
                bufferedMs = PlaybackService.instance?.bufferedAheadMs() ?: 0L
                delay(500)
                loadingTicks += 1
            }
        }
        val statusText = when {
            state.loadRetryCount > 0 -> "连接不稳定，正在重试（第 ${state.loadRetryCount} 次）…"
            loadingTicks >= 16 && bufferedMs <= 0L -> "仍在加载视频，暂未收到数据…"
            loadingTicks >= 16 -> "仍在加载视频，已缓冲 ${bufferedMs / 1000} 秒…"
            else -> null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
                if (statusText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (!state.isPlaying && !state.isBuffering) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(
                // This control is only shown while isPlaying=false and the player is not loading; issue an explicit play
                // rather than toggling based on playWhenReady during buffering.
                onClick = { PlaybackService.instance?.playPlayback() },
                shape = CircleShape,
                color = Color.White,
            ) {
                Box(Modifier.size(54.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color(0xFF172033), modifier = Modifier.size(32.dp))
                }
            }
        }
    }

    if (controlsVisible) {
        Column(
            Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xB8172033))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val seekThumbRadiusPx = with(LocalDensity.current) { 6.dp.toPx() }
            Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(state.key, state.durationMs) {
                    detectTapGestures { offset ->
                        val duration = (PlaybackService.instance?.duration() ?: 0L).takeIf { it > 0L } ?: state.durationMs
                        if (duration > 0L && size.width > 0) {
                            val seekableWidth = (size.width - 2f * seekThumbRadiusPx).coerceAtLeast(1f)
                            pos = ((offset.x - seekThumbRadiusPx) / seekableWidth).coerceIn(0f, 1f)
                            PlaybackService.instance?.seek((pos * duration).toLong())
                            scrubbing = false
                        }
                    }
                }
                .pointerInput(state.key, state.durationMs) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            scrubbing = true
                            if (size.width > 0) {
                                val seekableWidth = (size.width - 2f * seekThumbRadiusPx).coerceAtLeast(1f)
                                pos = ((offset.x - seekThumbRadiusPx) / seekableWidth).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = {
                            val duration = (PlaybackService.instance?.duration() ?: 0L).takeIf { it > 0L } ?: state.durationMs
                            if (duration > 0L) PlaybackService.instance?.seek((pos * duration).toLong())
                            scrubbing = false
                        },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            val seekableWidth = (size.width - 2f * seekThumbRadiusPx).coerceAtLeast(1f)
                            pos = ((change.position.x - seekThumbRadiusPx) / seekableWidth).coerceIn(0f, 1f)
                        }
                    }
                },
        ) {
            val height = 2.dp.toPx()
            val centerY = size.height / 2f
            val thumbRadius = seekThumbRadiusPx
            val trackWidth = (size.width - 2f * thumbRadius).coerceAtLeast(0f)
            val thumbCenterX = thumbRadius + trackWidth * pos.coerceIn(0f, 1f)
            val radius = CornerRadius(height / 2f, height / 2f)
            drawRoundRect(
                color = Color(0x66FFFFFF),
                topLeft = Offset(thumbRadius, centerY - height / 2f),
                size = Size(trackWidth, height),
                cornerRadius = radius,
            )
            val activeWidth = trackWidth * pos.coerceIn(0f, 1f)
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = Color(0xFF5890FF),
                    topLeft = Offset(thumbRadius, centerY - height / 2f),
                    size = Size(activeWidth, height),
                    cornerRadius = radius,
                )
            }
            drawCircle(
                color = Color(0xFF5890FF),
                radius = thumbRadius,
                center = Offset(thumbCenterX, centerY),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (state.isPlaying) PlaybackService.instance?.pausePlayback() else PlaybackService.instance?.playPlayback() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { speedExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Speed, contentDescription = "播放倍速", tint = Color.White, modifier = Modifier.size(21.dp))
                }
                DropdownMenu(expanded = speedExpanded, onDismissRequest = { speedExpanded = false }) {
                    speedOptions.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(if (value == speed) "✓ " + value + "×" else value.toString() + "×") },
                            onClick = {
                                speed = value
                                PlaybackService.instance?.setSpeed(value)
                                speedExpanded = false
                            },
                        )
                    }
                }
            }
            IconButton(
                onClick = { if (isFloating) onExitFloat() else onStartFloat() },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.PictureInPictureAlt,
                    contentDescription = if (isFloating) "关闭悬浮窗" else "打开悬浮窗",
                    tint = if (isFloating) Color(0xFF9CBFFF) else Color.White,
                    modifier = Modifier.size(21.dp),
                )
            }
            Box {
                IconButton(
                    onClick = { qualityExpanded = true },
                    enabled = state.qualityOptions.isNotEmpty(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.HighQuality,
                        contentDescription = "选择清晰度",
                        tint = if (state.qualityOptions.isNotEmpty()) Color.White else Color(0xFF777777),
                        modifier = Modifier.size(21.dp),
                    )
                }
                DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                    state.qualityOptions.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(if (quality == state.selectedQuality) "✓ " + quality else quality) },
                            onClick = {
                                qualityExpanded = false
                                PlaybackService.instance?.selectQuality(quality)
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun PlaybackMenuItem(
    icon: ImageVector,
    title: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = Modifier.fillMaxWidth(),
        text = {
            Text(
                text = title,
                color = if (enabled) OnBg else SubText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) SurfaceHigh else BgDark),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        if (selected) BrandBright else SubText
                    } else {
                        MutedGreen
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已启用",
                    tint = BrandBright,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun ControlBar(
    state: PlayerUiState,
    onImportSub: () -> Unit,
    onCopySub: () -> Unit,
) {
    val svc = PlaybackService.instance
    val isSpeakerBusy = state.progress.contains("人物识别") ||
        state.progress.contains("人物说话") ||
        state.progress.contains("分离")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = if (state.dubbingVolume > 0f) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                label = "配音",
                selected = state.dubbingVolume > 0f && state.lines.isNotEmpty() &&
                    state.noModelHint?.contains("配音模型") != true,
                enabled = state.lines.isNotEmpty() &&
                    state.noModelHint?.contains("配音模型") != true,
                onClick = { svc?.toggleDubbingVolume() },
            )
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = if (state.originalVolume > 0f) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                label = "原声",
                selected = state.originalVolume > 0f,
                onClick = { svc?.toggleOriginalVolume() },
            )
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Headphones,
                label = "仅听",
                selected = state.audioOnly,
                onClick = { svc?.toggleAudioOnly() },
            )
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Face,
                label = if (isSpeakerBusy) "识别中" else "人物",
                selected = false,
                enabled = state.lines.isNotEmpty() && !isSpeakerBusy,
                onClick = { svc?.autoAssignSpeakers() },
            )
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Subtitles,
                label = "导入字幕",
                selected = false,
                onClick = onImportSub,
            )
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.ContentCopy,
                label = "复制",
                selected = false,
                enabled = state.lines.isNotEmpty(),
                onClick = onCopySub,
            )
        }
    }
}

@Composable
private fun PlayerToolTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> Color.Transparent
                    selected -> SurfaceHigh
                    else -> Color.Transparent
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 5.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                !enabled -> SubText.copy(alpha = 0.35f)
                selected -> BrandBright
                else -> SubText
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = when {
                !enabled -> SubText.copy(alpha = 0.4f)
                selected -> BrandBright
                else -> SubText
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun SubtitleList(
    state: PlayerUiState,
    svc: PlaybackService?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    // Keep row highlighting locked to the video timeline in both playback modes.
    // A late TTS start must not postpone the visual subtitle effect.
    val activeSubtitleIndex = state.currentLine
    val hasActiveSubtitle = activeSubtitleIndex in state.lines.indices
    val activeSubtitlePulse by animateFloatAsState(
        targetValue = if (hasActiveSubtitle) 1.01f else 1f,
        animationSpec = tween(180),
        label = "subtitleActiveScale",
    )

    Column(
        modifier.fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 3.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.lines.isEmpty() && state.phase == Phase.READY) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("暂无字幕内容，可从顶部菜单导入字幕文件", color = MutedGreen, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            itemsIndexed(state.lines, key = { i, _ -> i }) { i, line ->
                val active = i == activeSubtitleIndex
                val activeScale = if (active) activeSubtitlePulse else 1f
                val translatedText = remember(line.text, line.zh) {
                    SubtitleParsers.preferredTranslationText(line)
                        ?.takeIf { it.isNotBlank() }
                        // Never display a progress string as if it were translated speech.
                        ?: "中文字幕暂不可用"
                }
                val sourceComparisonKey = remember(line.text) { subtitleComparisonKey(line.text) }
                val translatedComparisonKey = remember(translatedText) {
                    subtitleComparisonKey(translatedText)
                }
                // Keep the same bilingual active cue visible in both playback modes.
                val showOriginalText = active &&
                    line.text.isNotBlank() &&
                    sourceComparisonKey != translatedComparisonKey

                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (active) Color(0xFFF6F7F9) else Color.Transparent
                        )
                        .clickable(
                            enabled = svc != null && line.startMs >= 0L && line.endMs > line.startMs,
                        ) { svc?.seekAndPlay(line.startMs) }
                        .padding(horizontal = 10.dp, vertical = if (active) 6.dp else 5.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Text(
                        translatedText,
                        // Use identical emphasis whether the active cue is source-audio or dubbed playback.
                        color = if (active) BrandBright else SubText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (active) 16.sp else 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            scaleX = activeScale
                            scaleY = activeScale
                        },
                    )
                    if (showOriginalText) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            line.text,
                            color = BrandBright,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                }
            }
        }
    }
}


private val subtitleComparisonPattern = Regex("[\\p{P}\\p{S}\\s]")

private fun subtitleComparisonKey(text: String): String =
    text.trim().lowercase(java.util.Locale.ROOT).replace(subtitleComparisonPattern, "")

@Composable
private fun ProcessingProgressCard(state: PlayerUiState) {
    val source = VideoProcessingFlowPolicy.sourceKind(state.key)
    val flow = state.processingFlow
    val steps = VideoProcessingFlowPolicy.steps(source, flow)
    val isCancelled = state.phase == Phase.ERROR && state.error.orEmpty().contains("已取消处理")
    val waitingForTranscriptionEngine = state.waitingForTranscriptionEngine && state.phase != Phase.ERROR
    val waitingForDubbingEngine = state.waitingForDubbingEngine && state.phase != Phase.ERROR
    val displayPhase = if (waitingForDubbingEngine && state.phase == Phase.READY) Phase.SYNTHESIZING else state.phase
    val title = when {
        isCancelled -> "处理已取消"
        state.dubbingHasErrors -> "部分配音失败，相关字幕已恢复原声"
        waitingForDubbingEngine -> "等待配音引擎就绪"
        else -> VideoProcessingFlowPolicy.mainTitle(
            source = source,
            phase = displayPhase,
            flow = flow,
            isPaused = state.isProcessingPaused,
            waitingForTranscriptionEngine = waitingForTranscriptionEngine,
        )
    }
    val detail = when {
        state.noModelHint != null && state.phase != Phase.ERROR -> state.noModelHint.orEmpty()
        state.isProcessingPaused -> "已暂停 · " + state.progress.ifBlank { "点击继续处理以恢复当前任务" }
        state.progress.isNotBlank() -> state.progress
        else -> VideoProcessingFlowPolicy.defaultDetail(source, flow, state.phase, state.error)
    }
    val stageCount = steps.size.coerceAtLeast(1)
    val lastStageIndex = (stageCount - 1).coerceAtLeast(0)
    val rawStageIndex = VideoProcessingFlowPolicy.stageIndex(
        source = source,
        flow = flow,
        phase = displayPhase,
        failedPhase = state.failedPhase,
        progress = if (state.phase == Phase.ERROR) state.failedProgress else state.progress,
        waitingForTranscriptionEngine = waitingForTranscriptionEngine,
    )
    val stageIndex = rawStageIndex.coerceIn(0, lastStageIndex)
    val completed = VideoProcessingFlowPolicy.isCompleted(
        state.phase,
        waitingForTranscriptionEngine,
        waitingForDubbingEngine,
        state.dubbingHasErrors,
    )
    val stageNumber = if (completed) stageCount else stageIndex + 1
    val fraction = when (state.phase) {
        Phase.READY -> if (completed) 1f else null
        Phase.PARSING -> null
        Phase.DOWNLOADING_AUDIO -> progressPercent(state.progress)
        Phase.TRANSCRIBING -> progressRatio(state.progress) ?: progressPercent(state.progress)
        Phase.TRANSLATING -> progressRatio(state.progress)
            ?: if (state.totalLines > 0) {
                (state.translatedLineCount.coerceIn(0, state.totalLines).toFloat() / state.totalLines).coerceIn(0f, 1f)
            } else null
        Phase.SYNTHESIZING -> if (state.totalLines > 0) {
            (state.preparedCount.toFloat() / state.totalLines).coerceIn(0f, 1f)
        } else null
        else -> null
    }

    val pct = fraction?.coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF7FFFFFF))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val spin = rememberInfiniteTransition(label = "orangeProgressRing")
                val angle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1250, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "orangeProgressRingAngle",
                )
                Canvas(Modifier.size(30.dp)) {
                    val stroke = 4.dp.toPx()
                    drawArc(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                            listOf(Color(0xFFFFE0B2), Color(0xFFFFB74D), Color(0xFFFF7A18), Color(0x00FF7A18)),
                            center = center,
                        ),
                        startAngle = angle,
                        sweepAngle = 292f,
                        useCenter = false,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        ),
                    )
                }
                Text(title, color = OnBg, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                when {
                    isCancelled -> "已取消"
                    waitingForTranscriptionEngine -> "等待转写"
                    waitingForDubbingEngine -> "等待配音"
                    state.dubbingHasErrors -> "配音异常"
                    state.phase == Phase.READY && completed -> "完成"
                    state.phase == Phase.READY -> "待配音"
                    state.phase == Phase.ERROR -> "失败"
                    pct != null -> "${(pct * 100).toInt()}%"
                    else -> "处理中"
                },
                color = BrandBright,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            color = SubText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        // Determinate-only indicator: it never animates on its own. When no reliable
        // numeric measurement exists, keep it at zero and show the real stage text above.
        LinearProgressIndicator(
            progress = { pct ?: 0f },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = BrandBright,
            trackColor = Color(0xFFE7ECF3),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                waitingForTranscriptionEngine ->
                    "等待转写引擎就绪 · 阶段 ${stageNumber}/${stageCount}"
                waitingForDubbingEngine ->
                    "等待配音引擎就绪 · 阶段 ${stageNumber}/${stageCount}"
                state.dubbingHasErrors ->
                    "部分配音失败 · 失败字幕已恢复原声 · 阶段 ${stageNumber}/${stageCount}"
                state.phase == Phase.READY && completed -> "全部阶段已完成 · ${stageCount}/${stageCount}"
                state.phase == Phase.READY -> "配音阶段待完成 · 阶段 ${stageNumber}/${stageCount}"
                isCancelled -> "处理已取消 · 停在阶段 ${stageNumber}/${stageCount}"
                state.phase == Phase.ERROR -> "第 ${stageNumber}/${stageCount} 阶段失败 · 失败信息见上方提示"
                pct != null -> "阶段 ${stageNumber}/${stageCount} · 实际进度 ${(pct * 100).toInt()}%"
                else -> "阶段 ${stageNumber}/${stageCount} · 当前阶段暂无精确百分比"
            },
            color = SubText,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun progressPercent(progress: String): Float? {
    val match = Regex("(\\d+(?:\\.\\d+)?)%").find(progress) ?: return null
    return (match.groupValues[1].toFloatOrNull()?.div(100f))?.coerceIn(0f, 1f)
}

private fun progressRatio(progress: String): Float? {
    val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(progress) ?: return null
    val current = match.groupValues[1].toFloatOrNull() ?: return null
    val total = match.groupValues[2].toFloatOrNull() ?: return null
    if (total <= 0f) return null
    return (current / total).coerceIn(0f, 1f)
}

private fun phaseText(p: Phase, progress: String): String = when (p) {
    Phase.PARSING -> "解析视频信息…"
    Phase.DOWNLOADING_AUDIO -> progress.ifEmpty { "下载音频…" }
    Phase.TRANSCRIBING -> progress.ifEmpty { "语音转写…" }
    Phase.TRANSLATING -> progress.ifEmpty { "正在将原文字幕翻译成中文…" }
    Phase.SYNTHESIZING -> progress.ifEmpty { "配音准备…" }
    Phase.READY -> "就绪"
    Phase.ERROR -> "失败"
    Phase.IDLE -> ""
}

private fun LangLabel(code: String) = when (code) {
    "zh" -> "中"; "en" -> "英"; "ja" -> "日"; "ko" -> "韩"; "fr" -> "法"; "de" -> "德"
    "es" -> "西"; "pt" -> "葡"; "ru" -> "俄"; "it" -> "意"; "auto" -> "自动"
    else -> code
}
