package io.translatetube.local.ui.screens

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    // 打开视频：先确保服务在跑，再下发任务
    LaunchedEffect(videoId, localUri) {
        val key = if (videoId != null) "youtube:$videoId" else "local:${localUri ?: ""}"
        if (state.key != key) {
            var s = PlaybackService.instance
            if (s == null) {
                androidx.core.content.ContextCompat.startForegroundService(
                    ctx, android.content.Intent(ctx, PlaybackService::class.java)
                )
                var tries = 0
                while (PlaybackService.instance == null && tries < 50) { delay(100); tries++ }
                s = PlaybackService.instance
            }
            s?.open(key, videoId, localUri)
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
                    Text(state.title.ifEmpty { "载入中…" }, color = OnBg, style = MaterialTheme.typography.titleMedium, maxLines = 1)
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
                // 阶段进度覆盖层
                val phase = state.phase
                if (phase != Phase.READY && phase != Phase.SYNTHESIZING) {
                    Box(Modifier.fillMaxSize().background(Color(0xD9ECFAF1)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            if (phase == Phase.ERROR) {
                                Text("加载失败", color = OnBg, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(state.error ?: "", color = SubText, style = MaterialTheme.typography.bodySmall)
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

            // ===== 控制条 =====
            ControlBar(
                state = state,
                onStartFloat = {
                    if (!floating.canDraw()) {
                        floatingGranted = false
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    } else {
                        (tv.parent as? ViewGroup)?.removeView(tv)
                        val host = FrameLayout(ctx).apply { addView(tv, FrameLayout.LayoutParams(-1, -1)) }
                        floating.show(host, 640, 360)
                        isFloating = true
                    }
                },
                onExitFloat = {
                    floating.hide()
                    (tv.parent as? ViewGroup)?.removeView(tv)
                    frame.addView(tv, FrameLayout.LayoutParams(-1, -1))
                    isFloating = false
                },
                isFloating = isFloating,
                floatingGranted = floatingGranted,
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
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("subtitles", text)
                        )
                    }
                },
            )

            Spacer(Modifier.height(6.dp))

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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ControlBar(
    state: PlayerUiState,
    onStartFloat: () -> Unit,
    onExitFloat: () -> Unit,
    isFloating: Boolean,
    floatingGranted: Boolean,
    onImportSub: () -> Unit,
    onCopySub: () -> Unit,
) {
    val ctx = LocalContext.current
    val svc = PlaybackService.instance
    var pos by remember { mutableStateOf(0f) }
    var speed by remember { mutableStateOf(1.0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val d = PlaybackService.instance?.duration() ?: 0L
            val p = PlaybackService.instance?.position() ?: 0L
            pos = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = pos,
            onValueChange = { v ->
                pos = v
                val d = svc?.duration() ?: 0L
                if (d > 0) svc?.seek((v * d).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = BrandBright, activeTrackColor = Brand,
                inactiveTrackColor = SurfaceHigh,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(SubtitleParsers.fmtTime((pos * (PlaybackService.instance?.duration() ?: 0L)).toLong()), color = SubText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(SubtitleParsers.fmtTime(state.durationMs), color = SubText, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(onClick = { svc?.playPause() }, label = { Text(if (state.isPlaying) "⏸ 暂停" else "▶ 播放") })
            AssistChip(
                onClick = { svc?.toggleDub() },
                label = { Text(if (state.dubbingOn) "🔊 配音开" else "🔇 配音关") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (state.dubbingOn) SurfaceHigh else SurfaceDark,
                    labelColor = if (state.dubbingOn) BrandBright else SubText,
                ),
            )
            AssistChip(onClick = { svc?.toggleMute() }, label = {
                Text(if (state.muteOriginal) "原声静音" else "原声压低") })
            AssistChip(onClick = { svc?.toggleAudioOnly() }, label = { Text(if (state.audioOnly) "🎧 仅听中" else "🎧 仅听") })
            AssistChip(
                onClick = {
                    if (!floatingGranted) {
                        // 无悬浮窗权限：直接引导到系统授权页
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        }
                    } else if (isFloating) onExitFloat() else onStartFloat()
                },
                label = { Text(if (isFloating) "关浮窗" else if (floatingGranted) "悬浮窗" else "悬浮窗(需授权)") },
            )
        }
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(
                onClick = { svc?.autoAssignSpeakers() },
                label = { Text(if (state.progress.contains("人物识别") || state.progress.contains("人物说话") || state.progress.contains("分离")) "🎭 正在识别…" else "🎭 自动区分人物") },
                enabled = state.lines.isNotEmpty() && !state.progress.contains("人物识别") && !state.progress.contains("人物说话") && !state.progress.contains("分离"),
            )
            AssistChip(onClick = { onImportSub() }, label = { Text("📥 导入字幕") })
            AssistChip(onClick = { onCopySub() }, label = { Text("📋 复制字幕") })
            AssistChip(onClick = { speed = 0.75f; svc?.setSpeed(0.75f) }, label = { Text("0.75x", color = if (speed == 0.75f) BrandBright else SubText) })
            AssistChip(onClick = { speed = 1.0f; svc?.setSpeed(1.0f) }, label = { Text("1x", color = if (speed == 1.0f) BrandBright else SubText) })
            AssistChip(onClick = { speed = 1.25f; svc?.setSpeed(1.25f) }, label = { Text("1.25x", color = if (speed == 1.25f) BrandBright else SubText) })
            AssistChip(onClick = { speed = 1.5f; svc?.setSpeed(1.5f) }, label = { Text("1.5x", color = if (speed == 1.5f) BrandBright else SubText) })
            AssistChip(onClick = { speed = 2.0f; svc?.setSpeed(2.0f) }, label = { Text("2x", color = if (speed == 2.0f) BrandBright else SubText) })
        }
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
                    "点击每行右侧音色标签可手动区分人物；多音色需要选择 Kokoro。自动说话人识别尚未接入。",
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
                    Text(zh, color = if (active) OnBg else Color(0xFF4A5E52), style = MaterialTheme.typography.bodyLarge)
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
