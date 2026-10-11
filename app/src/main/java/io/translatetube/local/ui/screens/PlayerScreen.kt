package io.translatetube.local.ui.screens

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
                                CircularProgressIndicator(color = BrandBright, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(phaseText(phase, state.progress), color = OnBg, style = MaterialTheme.typography.bodyMedium)
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
            listState.animateScrollToItem((state.currentLine - 3).coerceAtLeast(0))
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
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (state.lines.isNotEmpty()) {
            item {
                Text(
                    "点击「自动区分人物」分析前 3 分钟的音轨；识别结果会自动分配 Kokoro 预设音色，也可逐句手动调整。",
                    color = SubText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (state.lines.isEmpty() && state.phase == Phase.READY) {
            item { Text("无字幕内容", color = MutedGreen, style = MaterialTheme.typography.bodyMedium) }
        }
        itemsIndexed(state.lines, key = { i, _ -> i }) { i, line ->
            val active = i == state.currentLine
            val zh = line.zh
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) SurfaceHigh else Color.Transparent)
                    .clickable { svc?.seek(line.startMs) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        line.text,
                        color = if (active) BrandBright else SubText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val voiceIds = listOf(-1, 0, 1, 2, 3)
                    val voiceIndex = voiceIds.indexOf(line.speakerId).let { if (it < 0) 0 else it }
                    AssistChip(
                        onClick = { svc?.setLineSpeaker(i, voiceIds[(voiceIndex + 1) % voiceIds.size]) },
                        label = { Text("🎙 ${speakerLabel(line.speakerId)}") },
                    )
                }
                if (!zh.isNullOrEmpty()) {
                    Text(zh, color = if (active) OnBg else SubText, style = MaterialTheme.typography.bodyLarge)
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
