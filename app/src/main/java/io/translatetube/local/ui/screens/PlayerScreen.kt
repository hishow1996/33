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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

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
    var subtitlesVisible by remember { mutableStateOf(true) }
    var advancedMenuExpanded by remember { mutableStateOf(false) }

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

    fun copySubtitles() {
        val sb = StringBuilder()
        for (line in state.lines) {
            val zh = line.zh ?: ""
            if (zh.isNotBlank()) sb.append(line.text).append('\n').append(zh).append('\n').append('\n')
        }
        val text = sb.toString().trim()
        if (text.isNotEmpty()) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("subtitles", text))
        }
    }

    Box(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
    ) {
        if (state.phase != Phase.READY) {
            VideoParsingPage(
                state = state,
                onBack = { exitFloating(); PlaybackService.instance?.saveProgress(); onBack() },
                onRetry = { retryNonce++ },
                onImportSubtitles = { subPicker.launch(arrayOf("*/*")) },
            )
        } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFFF2F6FC)).padding(horizontal = 8.dp)) {
            // ===== 顶部栏 =====
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    "←",
                    color = OnBg,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clip(CircleShape)
                        .clickable { exitFloating(); PlaybackService.instance?.saveProgress(); onBack() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("视频播放", color = OnBg, style = MaterialTheme.typography.titleLarge)
                    Text("外文原声 · 中文配音", color = SubText, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Box {
                    Text(
                        "•••",
                        color = SubText,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.clip(CircleShape)
                            .clickable { advancedMenuExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                    DropdownMenu(
                        expanded = advancedMenuExpanded,
                        onDismissRequest = { advancedMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (state.audioOnly) "恢复视频画面" else "仅听模式") },
                            onClick = {
                                advancedMenuExpanded = false
                                PlaybackService.instance?.toggleAudioOnly()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("识别并区分人物") },
                            onClick = {
                                advancedMenuExpanded = false
                                PlaybackService.instance?.autoAssignSpeakers()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入字幕文件") },
                            onClick = {
                                advancedMenuExpanded = false
                                subPicker.launch(arrayOf("*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("复制双语字幕") },
                            onClick = {
                                advancedMenuExpanded = false
                                copySubtitles()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("翻译与配音设置") },
                            onClick = {
                                advancedMenuExpanded = false
                                onGoSettings()
                            },
                        )
                    }
                }
            }

            // ===== 视频画面（常驻容器，浮窗时 tv 被摘走；竖屏视频自适应比例） =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.videoAspect)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF172033))
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

            PlaybackModeSelector(
                state = state,
                subtitlesVisible = subtitlesVisible,
                onOriginal = { PlaybackService.instance?.toggleMute() },
                onDub = { PlaybackService.instance?.toggleDub() },
                onToggleSubtitles = { subtitlesVisible = !subtitlesVisible },
            )

            Spacer(Modifier.height(10.dp))

            if (subtitlesVisible) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    SubtitleList(
                        state = state,
                        svc = PlaybackService.instance,
                        listState = listState,
                        onCopy = { copySubtitles() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(24.dp),
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

    // 字幕只在当前配音行接近可视区域边缘时滚动，避免每句都重新启动滚动动画。
    // 用 rememberUpdatedState 让长生命周期协程始终读取最新播放行，不会闭包住旧 state。
    val autoScrollTarget = rememberUpdatedState(state.currentLine to state.lines.size)
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
    onImportSubtitles: () -> Unit,
) {
    val currentStage = when (state.phase) {
        Phase.IDLE, Phase.PARSING, Phase.ERROR -> 0
        Phase.DOWNLOADING_AUDIO -> 1
        Phase.TRANSCRIBING -> 2
        Phase.TRANSLATING -> 3
        Phase.SYNTHESIZING -> 4
        Phase.READY -> 5
    }
    val steps = listOf("获取视频信息", "解析视频资源", "下载或读取字幕", "翻译字幕", "生成配音")
    val fraction = when (state.phase) {
        Phase.SYNTHESIZING -> if (state.totalLines > 0) {
            (state.preparedUntil.toFloat() / state.totalLines).coerceIn(0f, 1f)
        } else null
        else -> progressRatio(state.progress) ?: progressPercent(state.progress)
    }
    val mainTitle = when (state.phase) {
        Phase.ERROR -> "视频处理失败"
        Phase.PARSING -> "正在处理视频"
        Phase.DOWNLOADING_AUDIO -> "正在准备视频资源"
        Phase.TRANSCRIBING -> "正在获取或识别字幕"
        Phase.TRANSLATING -> "正在翻译字幕"
        Phase.SYNTHESIZING -> "正在生成配音"
        else -> "正在准备视频"
    }
    val detail = state.progress.ifBlank {
        when (state.phase) {
            Phase.ERROR -> state.error ?: "处理失败，请重试或导入字幕文件"
            Phase.PARSING -> "正在获取视频信息和可用字幕"
            Phase.DOWNLOADING_AUDIO -> "正在解析视频资源并准备音轨"
            Phase.TRANSCRIBING -> "正在读取字幕或识别语音"
            Phase.TRANSLATING -> "正在将字幕翻译成中文"
            Phase.SYNTHESIZING -> "正在准备中文配音"
            else -> "正在检查视频链接"
        }
    }

    Column(
        Modifier.fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFFF2F6FC))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "←",
                color = OnBg,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.phase == Phase.ERROR) "视频解析失败" else "视频解析",
                color = OnBg,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text("•••", color = SubText, style = MaterialTheme.typography.titleMedium)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F3F8)),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(SurfaceHigh),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Subtitles, contentDescription = null, tint = BrandBright, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(mainTitle, color = OnBg, style = MaterialTheme.typography.titleLarge)
                        Text(detail, color = SubText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(17.dp))
                LinearProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = Brand,
                    trackColor = Color(0xFFE6ECF5),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (fraction != null) "当前进度 · " + (fraction * 100).toInt() + "%"
                    else "当前阶段 · 正在处理中",
                    color = SubText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F3F8)),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text("处理进度", color = OnBg, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                steps.forEachIndexed { index, label ->
                    val done = state.phase != Phase.ERROR && index < currentStage
                    val active = state.phase != Phase.ERROR && index == currentStage
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(27.dp).clip(CircleShape)
                                .background(when {
                                    done -> Color(0xFFE7F5ED)
                                    active -> Color(0xFFE8F0FF)
                                    else -> Color(0xFFF0F3F7)
                                }),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            when {
                                done -> Text("✓", color = Color(0xFF21945A), style = MaterialTheme.typography.titleMedium)
                                active -> CircularProgressIndicator(
                                    modifier = Modifier.size(17.dp),
                                    strokeWidth = 2.dp,
                                    color = BrandBright,
                                )
                                else -> Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFB7C2D1)))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            label,
                            color = if (active) BrandBright else OnBg,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                done -> "完成"
                                active -> if (fraction != null) ((fraction * 100).toInt().toString() + "%") else "进行中"
                                state.phase == Phase.ERROR && index == 0 -> "检查失败"
                                else -> ""
                            },
                            color = when {
                                done -> Color(0xFF21945A)
                                active -> BrandBright
                                state.phase == Phase.ERROR && index == 0 -> DangerRed
                                else -> SubText
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (state.phase == Phase.ERROR) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.error ?: detail,
                        color = DangerRed,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Color.White),
                    ) { Text("重试") }
                }
                HorizontalDivider(color = OutlineSoft, modifier = Modifier.padding(vertical = 13.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Filled.Subtitles, contentDescription = null, tint = BrandBright, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("已有字幕文件？", color = OnBg, style = MaterialTheme.typography.titleMedium)
                        Text("可导入本地字幕，减少不必要的处理", color = SubText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onImportSubtitles,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(15.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCCD9EC)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright),
                ) {
                    Icon(Icons.Filled.Subtitles, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("导入字幕文件", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
            selected = !state.muteOriginal,
            onClick = onOriginal,
        )
        ModeButton(
            modifier = Modifier.weight(1f),
            title = "中文配音",
            glyph = "≋",
            selected = state.dubbingOn,
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
    Surface(
        modifier = modifier.height(72.dp),
        onClick = onClick,
        color = if (selected) Color(0xFFE4EDFF) else Color.White,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(0xFFE4EDFF) else OutlineSoft),
    ) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 8.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(glyph, color = BrandBright, style = MaterialTheme.typography.titleLarge)
            Text(title, color = if (selected) BrandBright else OnBg, style = MaterialTheme.typography.bodyMedium)
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

    if (!state.isPlaying) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(
                onClick = { svc?.playPause() },
                shape = CircleShape,
                color = Color.White,
            ) {
                Box(Modifier.size(54.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color(0xFF172033), modifier = Modifier.size(32.dp))
                }
            }
        }
    }

    Column(
        Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color(0xB8172033))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
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
            val height = 2.dp.toPx()
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
                    color = Color(0xFF5890FF),
                    topLeft = Offset(0f, centerY - height / 2f),
                    size = Size(activeWidth, height),
                    cornerRadius = radius,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(onClick = { svc?.playPause() }, modifier = Modifier.size(36.dp)) {
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
                                svc?.selectQuality(quality)
                            },
                        )
                    }
                }
            }
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
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = if (state.dubbingOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                label = "配音",
                selected = state.dubbingOn && state.lines.isNotEmpty() &&
                    state.noModelHint?.contains("配音模型") != true,
                enabled = state.lines.isNotEmpty() &&
                    state.noModelHint?.contains("配音模型") != true,
                onClick = { svc?.toggleDub() },
            )
            PlayerToolTile(
                modifier = Modifier.weight(1f),
                icon = if (state.muteOriginal) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                label = "原声",
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
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("中文字幕", color = OnBg, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制双语字幕", tint = SubText)
            }
        }
        HorizontalDivider(color = OutlineSoft, thickness = 1.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.lines.isEmpty() && state.phase == Phase.READY) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("暂无字幕内容，可从顶部菜单导入字幕文件", color = MutedGreen, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            itemsIndexed(state.lines, key = { i, _ -> i }) { i, line ->
                val active = i == state.currentLine
                val activeDub = active && state.dubbingOn
                val dubbingScale by rememberInfiniteTransition(label = "dubbingLinePulse").animateFloat(
                    initialValue = 1f,
                    targetValue = if (activeDub) 1.025f else 1f,
                    animationSpec = infiniteRepeatable(animation = tween(850), repeatMode = RepeatMode.Reverse),
                    label = "dubbingLineScale",
                )
                val translatedText = line.zh
                    ?.let { SubtitleParsers.cleanTranslatedText(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: "正在翻译…"
                val showOriginalText = activeDub && line.text.isNotBlank() &&
                    subtitleComparisonKey(line.text) != subtitleComparisonKey(translatedText)

                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            when {
                                activeDub -> Color(0xFFF6F7F9)
                                active -> SurfaceHigh.copy(alpha = 0.34f)
                                else -> Color.Transparent
                            }
                        )
                        .clickable { svc?.seek(line.startMs) }
                        .padding(horizontal = 14.dp, vertical = if (activeDub) 17.dp else 11.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    if (showOriginalText) {
                        Text(
                            line.text,
                            color = Color(0xFF626873),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                        )
                    }
                    Text(
                        translatedText,
                        color = when {
                            activeDub -> Color(0xFF111318)
                            active -> OnBg
                            else -> SubText
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (activeDub) 23.sp else if (active) 19.sp else 17.sp,
                            fontWeight = when {
                                activeDub -> FontWeight.Black
                                active -> FontWeight.SemiBold
                                else -> FontWeight.Normal
                            },
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().graphicsLayer {
                            scaleX = dubbingScale
                            scaleY = dubbingScale
                        },
                    )
                    if (active) {
                        Spacer(Modifier.height(7.dp))
                        Surface(
                            onClick = {
                                val ids = listOf(-1, 0, 1, 2, 3)
                                val index = ids.indexOf(line.speakerId).let { if (it < 0) 0 else it }
                                svc?.setLineSpeaker(i, ids[(index + 1) % ids.size])
                            },
                            shape = RoundedCornerShape(50),
                            color = if (activeDub) Color(0xFFE3E6EB) else SurfaceHigh.copy(alpha = 0.75f),
                            contentColor = if (activeDub) Color(0xFF30343B) else SubText,
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
}


private fun subtitleComparisonKey(text: String): String =
    text.trim().lowercase().replace(Regex("[\\p{P}\\p{S}\\s]"), "")

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
