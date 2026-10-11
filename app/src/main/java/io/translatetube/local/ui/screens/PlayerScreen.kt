package io.translatetube.local.ui.screens

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.translatetube.local.floating.FloatingManager
import io.translatetube.local.player.PlaybackService
import io.translatetube.local.player.PlayerUiState
import io.translatetube.local.player.Phase
import io.translatetube.local.subs.SubLine
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

@Composable
fun PlayerScreen(
    videoId: String?,
    localUri: android.net.Uri?,
    onBack: () -> Unit,
    onGoSettings: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val state by PlaybackService.ui.collectAsState()
    val listState = rememberLazyListState()

    // 错误时允许用户显式重试同一个视频；不监听 phase，避免失败后自动重试循环。
    var retryNonce by remember { mutableStateOf(0) }
    var handledRetryNonce by remember { mutableStateOf(0) }

    // 打开视频：先确保服务在跑，再下发任务
    LaunchedEffect(videoId, localUri, retryNonce) {
        val key = if (videoId != null) "youtube:$videoId" else "local:${localUri ?: ""}"
        val retryRequested = retryNonce != handledRetryNonce
        if (state.key != key || retryRequested) {
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
                    s.open(key, videoId, localUri)
                }
            } catch (e: Throwable) {
                // Do not let a non-fatal runtime/linkage error in service startup terminate the
                // Compose coroutine and process. VM-fatal conditions must still propagate.
                if (e is VirtualMachineError || e is ThreadDeath) throw e
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
    var floatingGranted by remember { mutableStateOf(floating.canDraw()) }
    // 从系统授权页返回时刷新悬浮窗权限状态
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                floatingGranted = floating.canDraw()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val tv = remember { TextureView(ctx) }
    val frame = remember { FrameLayout(ctx) }

    // 服务就绪后挂画面（等 instance 出现，时序安全）
    LaunchedEffect(Unit) {
        var tries = 0
        while (PlaybackService.instance == null && tries < 100) { delay(100); tries++ }
        PlaybackService.instance?.attachSurface(tv)
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
            (tv.parent as? ViewGroup)?.removeView(tv)
            frame.addView(tv, FrameLayout.LayoutParams(-1, -1))
            isFloating = false
        }
    }

    fun startFloatingWindow() {
        if (!floating.canDraw()) {
            floatingGranted = false
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    )
                )
            }
            return
        }
        (tv.parent as? ViewGroup)?.removeView(tv)
        val host = FrameLayout(ctx).apply {
            addView(tv, FrameLayout.LayoutParams(-1, -1))
        }
        floating.show(host, 640, 360)
        isFloating = true
    }

    // 系统返回键：存进度回主页，而不是退出应用
    androidx.activity.compose.BackHandler {
        exitFloating()
        PlaybackService.instance?.saveProgress()
        onBack()
    }

    // 导入外部字幕（SRT/VTT）
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { PlaybackService.instance?.importSubtitles(it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            // ===== 顶部栏 =====
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("←", color = OnBg, style = MaterialTheme.typography.titleLarge, modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { exitFloating(); PlaybackService.instance?.saveProgress(); onBack() }
                    .padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title.ifEmpty { if (state.phase == Phase.ERROR) "加载失败" else "载入中…" }, color = OnBg, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text("${if (state.sourceLang.isNotEmpty()) LangLabel(state.sourceLang) + " → " else ""}中文 · ${state.engineName}",
                        color = SubText, style = MaterialTheme.typography.bodySmall)
                }
                if (state.phase == Phase.READY || state.phase == Phase.SYNTHESIZING) {
                    Text("配音 ${state.preparedUntil}/${state.totalLines}", color = if (state.preparedUntil >= state.totalLines && state.totalLines > 0) BrandBright else WarnYellow, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ===== 视频画面（常驻容器，浮窗时 tv 被摘走；竖屏视频自适应比例） =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.videoAspect)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceHigh)
            ) {
                AndroidView(
                    factory = {
                        frame.addView(tv, FrameLayout.LayoutParams(-1, -1))
                        PlaybackService.instance?.attachSurface(tv)
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
                // 阶段进度覆盖层
                val phase = state.phase
                if (phase != Phase.READY && phase != Phase.SYNTHESIZING) {
                    Box(Modifier.fillMaxSize().background(Color(0xD9F4F8FF)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            if (phase == Phase.ERROR) {
                                Text("加载失败", color = OnBg, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(state.error ?: "", color = SubText, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { retryNonce++ }) {
                                    Text("重试", color = BrandBright)
                                }
                            } else {
                                ProcessingProgressCard(state = state)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 模型未下载引导条（点击跳设置）
            state.noModelHint?.let { hint ->
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onGoSettings() },
                    color = Color(0xFFFFF8E1),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("⚠️", color = WarnYellow)
                        Spacer(Modifier.width(8.dp))
                        Text(hint, color = Color(0xFF5D4037), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("去设置 →", color = AccentBlue, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // ===== 简洁的音频与字幕工具面板 =====
            ControlBar(
                state = state,
                onImportSub = { subPicker.launch(arrayOf("*/*")) },
                onCopySub = {
                    val sb = StringBuilder()
                    for (l in state.lines) {
                        val zh = l.zh ?: ""
                        if (zh.isNotBlank()) sb.append(l.text).append('\n').append(zh).append('\n').append('\n')
                    }
                    val text = sb.toString().trim()
                    if (text.isNotEmpty()) {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("subtitles", text))
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            // ===== 双语字幕列表 =====
            SubtitleList(state, PlaybackService.instance, listState)
        }
    }

    // 字幕自动跟随滚动
    LaunchedEffect(state.currentLine) {
        if (state.currentLine >= 0) {
            // Keep the currently dubbed subtitle at the visual center; surrounding lines move around it.
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val centerOffset = if (viewportHeight > 0) -(viewportHeight / 2) else 0
            listState.animateScrollToItem(state.currentLine, scrollOffset = centerOffset)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.VideoOverlayControls(
    state: PlayerUiState,
    isFloating: Boolean,
    onStartFloat: () -> Unit,
    onExitFloat: () -> Unit,
) {
    val svc = PlaybackService.instance
    var pos by remember(state.key) { mutableStateOf(0f) }
    var scrubbing by remember(state.key) { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var speedExpanded by remember { mutableStateOf(false) }
    var speed by remember(state.key) { mutableStateOf(1.0f) }
    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    LaunchedEffect(state.key) {
        while (true) {
            if (!scrubbing) {
                val duration = svc?.duration() ?: 0L
                val position = svc?.position() ?: 0L
                pos = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }
            delay(300)
        }
    }

    Column(
        Modifier
            .align(androidx.compose.ui.Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color(0xB8000000))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = { svc?.playPause() },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            val trackDuration = (svc?.duration() ?: 0L).takeIf { it > 0L } ?: state.durationMs
            Text(
                "${SubtitleParsers.fmtTime((pos * trackDuration).toLong())} / ${SubtitleParsers.fmtTime(trackDuration)}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { qualityExpanded = true },
                    enabled = state.qualityOptions.isNotEmpty(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.HighQuality,
                        contentDescription = "选择清晰度",
                        tint = if (state.qualityOptions.isNotEmpty()) Color.White else Color(0xFF777777),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = qualityExpanded,
                    onDismissRequest = { qualityExpanded = false },
                ) {
                    state.qualityOptions.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(if (quality == state.selectedQuality) "✓ $quality" else quality) },
                            onClick = {
                                qualityExpanded = false
                                svc?.selectQuality(quality)
                            },
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { speedExpanded = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "播放倍速",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = speedExpanded,
                    onDismissRequest = { speedExpanded = false },
                ) {
                    speedOptions.forEach { value ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (value == speed) "✓ ${value}×" else "${value}×"
                                )
                            },
                            onClick = {
                                speed = value
                                svc?.setSpeed(value)
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
                    imageVector = Icons.Filled.PictureInPictureAlt,
                    contentDescription = if (isFloating) "关闭悬浮窗" else "打开悬浮窗",
                    tint = if (isFloating) BrandBright else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .pointerInput(state.key, state.durationMs) {
                    detectTapGestures { offset ->
                        val duration = (svc?.duration() ?: 0L).takeIf { it > 0L } ?: state.durationMs
                        if (duration > 0L && size.width > 0) {
                            pos = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            svc?.seek((pos * duration).toLong())
                            scrubbing = false
                        }
                    }
                }
                .pointerInput(state.key, state.durationMs) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            scrubbing = true
                            if (size.width > 0) pos = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val duration = (svc?.duration() ?: 0L).takeIf { it > 0L } ?: state.durationMs
                            if (duration > 0L) svc?.seek((pos * duration).toLong())
                            scrubbing = false
                        },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ ->
                        change.consume()
                        if (size.width > 0) pos = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    }
                },
        ) {
            val height = 1.5.dp.toPx()
            val centerY = size.height / 2f
            val radius = CornerRadius(height / 2f, height / 2f)
            drawRoundRect(
                color = Color(0x66FFFFFF),
                topLeft = Offset(0f, centerY - height / 2f),
                size = Size(size.width, height),
                cornerRadius = radius,
            )
            val activeWidth = size.width * pos.coerceIn(0f, 1f)
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = BrandBright,
                    topLeft = Offset(0f, centerY - height / 2f),
                    size = Size(activeWidth, height),
                    cornerRadius = radius,
                )
            }
            drawCircle(
                color = BrandBright,
                radius = 2.4.dp.toPx(),
                center = Offset(activeWidth.coerceIn(0f, size.width), centerY),
            )
        }
    }
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
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlayerToolTile(
                    modifier = Modifier.weight(1f),
                    icon = if (state.dubbingOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    label = when {
                        state.lines.isEmpty() -> "等待字幕"
                        state.noModelHint?.contains("配音模型") == true -> "配音模型未就绪"
                        state.dubbingOn -> "配音开启"
                        else -> "配音关闭"
                    },
                    selected = state.dubbingOn && state.lines.isNotEmpty() &&
                        state.noModelHint?.contains("配音模型") != true,
                    enabled = state.lines.isNotEmpty() &&
                        state.noModelHint?.contains("配音模型") != true,
                    onClick = { svc?.toggleDub() },
                )
                PlayerToolTile(
                    modifier = Modifier.weight(1f),
                    icon = if (state.muteOriginal) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    label = if (state.muteOriginal) "原声静音" else if (state.dubbingOn) "原声压低" else "原声开启",
                    selected = state.muteOriginal,
                    onClick = { svc?.toggleMute() },
                )
                PlayerToolTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Headphones,
                    label = "仅听",
                    selected = state.audioOnly,
                    onClick = { svc?.toggleAudioOnly() },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlayerToolTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Face,
                    label = if (isSpeakerBusy) "识别中" else "区分人物",
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
                    label = "复制字幕",
                    selected = false,
                    enabled = state.lines.isNotEmpty(),
                    onClick = onCopySub,
                )
            }
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
            .padding(horizontal = 3.dp, vertical = 7.dp),
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
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            color = when {
                !enabled -> SubText.copy(alpha = 0.4f)
                selected -> BrandBright
                else -> SubText
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun SubtitleList(
    state: PlayerUiState,
    svc: PlaybackService?,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 180.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.lines.isEmpty() && state.phase == Phase.READY) {
            item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("无字幕内容", color = MutedGreen, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        itemsIndexed(state.lines, key = { i, _ -> i }) { i, line ->
            val active = i == state.currentLine
            val distance = if (state.currentLine >= 0) kotlin.math.abs(i - state.currentLine) else Int.MAX_VALUE
            val targetAlpha = when {
                state.currentLine < 0 -> 0.72f
                distance == 0 -> 1f
                distance == 1 -> 0.54f
                distance == 2 -> 0.30f
                distance == 3 -> 0.18f
                else -> 0.10f
            }
            val textAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(420),
                label = "subtitleFocusFade",
            )
            val scale by animateFloatAsState(
                targetValue = if (active) 1f else 0.97f,
                animationSpec = tween(360),
                label = "subtitleFocusScale",
            )
            val background by animateColorAsState(
                targetValue = if (active) SurfaceHigh.copy(alpha = 0.28f) else Color.Transparent,
                animationSpec = tween(320),
                label = "subtitleFocusBackground",
            )
            val color by animateColorAsState(
                targetValue = if (active) OnBg else SubText,
                animationSpec = tween(320),
                label = "subtitleFocusText",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(background)
                    .animateContentSize(tween(300))
                    .clickable { svc?.seek(line.startMs) }
                    .padding(horizontal = 16.dp, vertical = if (active) 16.dp else 10.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text(
                    line.text,
                    color = color.copy(alpha = textAlpha),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = if (active) 17.sp else 15.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = scale; scaleY = scale },
                )
                val translatedText = line.zh
                if (!translatedText.isNullOrBlank()) {
                    Text(
                        translatedText,
                        color = color.copy(alpha = textAlpha),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (active) 24.sp else 19.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .padding(top = 4.dp),
                    )
                }
                if (active) {
                    Spacer(Modifier.height(8.dp))
                    // Keep speaker assignment available without the bulky "人物1·默认" chip.
                    Surface(
                        onClick = {
                            val ids = listOf(-1, 0, 1, 2, 3)
                            val index = ids.indexOf(line.speakerId).let { if (it < 0) 0 else it }
                            svc?.setLineSpeaker(i, ids[(index + 1) % ids.size])
                        },
                        shape = RoundedCornerShape(50),
                        color = SurfaceHigh.copy(alpha = 0.75f),
                        contentColor = SubText,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Filled.Face, contentDescription = "切换配音人物", modifier = Modifier.size(16.dp))
                            Text(speakerLabel(line.speakerId), style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun speakerLabel(id: Int): String = when (id) {
    -1 -> "人物1·默认"
    0 -> "人物2"
    1 -> "人物3"
    2 -> "人物4"
    3 -> "人物5"
    else -> "音色$id"
}


@Composable
private fun ProcessingProgressCard(state: PlayerUiState) {
    val title: String
    val detail: String
    val stageIndex: Int
    val fraction: Float?

    when (state.phase) {
        Phase.PARSING -> {
            title = "正在分析视频"
            detail = state.progress.ifBlank { "获取视频信息与可用字幕、音轨" }
            stageIndex = 0
            fraction = null
        }
        Phase.DOWNLOADING_AUDIO -> {
            title = "正在准备音轨"
            detail = state.progress.ifBlank { "检查并读取视频音频" }
            stageIndex = 1
            fraction = progressPercent(state.progress)
        }
        Phase.TRANSCRIBING -> {
            title = "正在识别语音"
            detail = state.progress.ifBlank { "把语音转换成带时间轴的文本" }
            stageIndex = 2
            fraction = progressRatio(state.progress) ?: progressPercent(state.progress)
        }
        Phase.TRANSLATING -> {
            title = "正在翻译字幕"
            detail = state.progress.ifBlank { "逐句翻译并整理字幕时间轴" }
            stageIndex = 3
            fraction = progressRatio(state.progress)
                ?: if (state.totalLines > 0) {
                    (state.lines.count { !it.zh.isNullOrBlank() }.toFloat() / state.totalLines).coerceIn(0f, 1f)
                } else null
        }
        Phase.SYNTHESIZING -> {
            title = "正在准备配音"
            detail = state.progress.ifBlank { "按字幕顺序生成语音" }
            stageIndex = 4
            fraction = if (state.totalLines > 0) {
                (state.preparedUntil.toFloat() / state.totalLines).coerceIn(0f, 1f)
            } else null
        }
        else -> {
            title = "正在处理视频"
            detail = state.progress
            stageIndex = 0
            fraction = null
        }
    }

    val pct = fraction?.coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF7FFFFFF))
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(title, color = OnBg, style = MaterialTheme.typography.titleMedium)
            Text(
                pct?.let { "${(it * 100).toInt()}%" } ?: "处理中",
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
            if (pct != null) "阶段 ${stageIndex + 1}/5 · 实际进度 ${(pct * 100).toInt()}%"
            else "阶段 ${stageIndex + 1}/5 · 当前阶段暂无精确百分比",
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
    Phase.TRANSLATING -> progress.ifEmpty { "翻译字幕…" }
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
