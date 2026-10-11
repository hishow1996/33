package io.translatetube.local.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.translatetube.local.ui.theme.*
import io.translatetube.local.floating.FloatingManager

@Composable
fun SettingsScreen(onBack: () -> Unit, onApiManager: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var ttsEngine by remember { mutableStateOf(prefs.getString("tts_engine", "kokoro") ?: "kokoro") }
    var speaker by remember { mutableStateOf(prefs.getInt("kokoro_speaker", 14).toFloat()) }
    val floatingManager = remember { FloatingManager(ctx) }
    var canFloat by remember { mutableStateOf(floatingManager.canDraw()) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) canFloat = floatingManager.canDraw()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                Text("‹ 返回", color = SubText, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.weight(1f))
            Text("大象翻译", color = SubText, style = MaterialTheme.typography.labelLarge)
        }
        Text("设置", style = MaterialTheme.typography.headlineLarge, color = OnBg)
        Spacer(Modifier.height(4.dp))
        Text("管理语音、翻译与播放体验", color = SubText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))

        // ===== 语音模型（方案 B：软件内下载） =====
        val dlManager = remember { (ctx.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload }
        val ttsState by dlManager.tts.collectAsState()
        val asrState by dlManager.asr.collectAsState()
        var ttsReady by remember { mutableStateOf(io.translatetube.local.data.ModelFiles.ttsReady(ctx)) }
        var asrReady by remember { mutableStateOf(io.translatetube.local.data.ModelFiles.asrReady(ctx)) }
        var confirmKind by remember { mutableStateOf<io.translatetube.local.data.ModelKind?>(null) }

        Section("语音模型") {
            Text("配音和无字幕转写使用不同模型；未下载时可在对应卡片中下载。", color = MutedGreen, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))

            // —— 配音模型 ——
            ModelRow(title = "配音模型", subtitle = "Kokoro v1.1 · 中文神经网络配音",
                state = ttsState, ready = ttsReady, pkgMB = 140,
                readyText = "已就绪，可使用神经网络配音",
                onDownload = { confirmKind = io.translatetube.local.data.ModelKind.TTS })
            Spacer(Modifier.height(8.dp))
            ModelRow(title = "语音转写模型", subtitle = "Whisper base · 用于没有字幕的视频",
                state = asrState, ready = asrReady, pkgMB = 198,
                readyText = "已就绪，可识别视频语音",
                onDownload = { confirmKind = io.translatetube.local.data.ModelKind.ASR })
        }

        // 下载确认弹窗（规格：右侧确定 / 左侧取消，显示包大小）
        confirmKind?.let { kind ->
            val name = if (kind == io.translatetube.local.data.ModelKind.TTS) "配音模型（Kokoro）" else "转写模型（Whisper）"
            val size = if (kind == io.translatetube.local.data.ModelKind.TTS) "140MB（解压后约 207MB）" else "198MB（解压后约 154MB）"
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmKind = null },
                title = { Text("确定下载吗？", color = OnBg) },
                text = {
                    Column {
                        Text(name, color = OnBg, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("下载包大小：$size", color = SubText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("建议使用 Wi-Fi 网络，下载完成后自动解压安装。", color = MutedGreen, style = MaterialTheme.typography.bodySmall)
                    }
                },
                // 左：取消
                dismissButton = {
                    TextButton(onClick = { confirmKind = null }) { Text("取消", color = SubText) }
                },
                // 右：确定
                confirmButton = {
                    TextButton(onClick = {
                        dlManager.download(kind)
                        confirmKind = null
                    }) { Text("确定", color = BrandBright) }
                },
                containerColor = SurfaceDark,
            )
        }

        Section("配音引擎") {
            EngineOption("kokoro", "Kokoro 神经网络（推荐）", "自然度高，首次合成略慢", ttsEngine) { e ->
                ttsEngine = e; prefs.edit().putString("tts_engine", e).apply()
            }
            EngineOption("system", "系统 TTS", "速度快，朗读腔", ttsEngine) { e ->
                ttsEngine = e; prefs.edit().putString("tts_engine", e).apply()
            }
            if (ttsEngine == "kokoro") {
                Text("中文声音试听序号：${speaker.toInt()}", color = SubText, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = speaker, onValueChange = { speaker = it },
                    onValueChangeFinished = { prefs.edit().putInt("kokoro_speaker", speaker.toInt()).apply() },
                    valueRange = 0f..54f,
                    colors = SliderDefaults.colors(thumbColor = BrandBright, activeTrackColor = Brand),
                )
                Text("0-54 为模型内声音编号，男女声交替，可试听后选定", color = MutedGreen, style = MaterialTheme.typography.bodySmall)
            }
            Text("切换后对下一次打开的视频生效（当前视频不变）。", color = MutedGreen, style = MaterialTheme.typography.bodySmall)
        }

        Section("翻译引擎") {
            Text("选择离线翻译或配置在线服务。", color = SubText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceHigh)
                .clickable(onClick = onApiManager).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("翻译引擎与 API", color = OnBg, style = MaterialTheme.typography.titleSmall)
                    Text("本地离线、Google、OpenAI 兼容及其他服务", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = BrandBright, style = MaterialTheme.typography.headlineMedium)
            }
        }

        Section("悬浮窗") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (canFloat) "权限已开启" else "需要开启悬浮窗权限", color = OnBg, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (canFloat) "可以在其他应用上方显示播放窗口" else "开启后才能使用全局悬浮播放",
                        color = if (canFloat) BrandBright else WarnYellow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!canFloat) {
                    Button(
                        onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("去开启") }
                }
            }
        }

        Section("存储") {
            var cacheSize by remember { mutableStateOf(formatCacheSize(ctx)) }
            var showClearConfirm by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("临时缓存", color = OnBg, style = MaterialTheme.typography.titleSmall)
                    Text("当前占用 $cacheSize", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright),
                ) { Text("清理缓存") }
            }
            Spacer(Modifier.height(5.dp))
            Text("清理会删除配音音频和转写临时文件，不会删除历史记录或收藏。", color = SubText, style = MaterialTheme.typography.bodySmall)
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("清理临时缓存？", color = OnBg) },
                    text = { Text("已生成的配音音频可能需要重新合成。历史记录和收藏不会受影响。", color = SubText) },
                    confirmButton = {
                        TextButton(onClick = {
                            clearAppCache(ctx)
                            cacheSize = formatCacheSize(ctx)
                            showClearConfirm = false
                        }) { Text("确认清理", color = DangerRed) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirm = false }) { Text("取消", color = SubText) }
                    },
                    containerColor = SurfaceDark,
                )
            }
        }

        Section("关于") {
            Text("大象翻译 v" + io.translatetube.local.BuildConfig.VERSION_NAME, color = OnBg, style = MaterialTheme.typography.titleSmall)
            Text("离线翻译 · 本地神经配音 · 悬浮播放", color = SubText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceDark)
        .padding(horizontal = 16.dp, vertical = 15.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OnBg)
        Spacer(Modifier.height(12.dp))
        content()
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun EngineOption(
    id: String, title: String, desc: String,
    current: String, onPick: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (current == id) SurfaceHigh else SurfaceDark)
        .clickable { onPick(id) }.padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current == id, onClick = { onPick(id) },
            colors = RadioButtonDefaults.colors(selectedColor = BrandBright))
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(title, color = OnBg, style = MaterialTheme.typography.bodyLarge)
            Text(desc, color = SubText, style = MaterialTheme.typography.bodySmall)
        }
        if (current == id) Text("已选", color = BrandBright, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ModelRow(
    title: String, subtitle: String,
    state: io.translatetube.local.data.ModelState, ready: Boolean, pkgMB: Int,
    readyText: String, onDownload: () -> Unit,
) {
    val dl = (LocalContext.current.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload
    val isReady = ready || state.status == io.translatetube.local.data.ModelStatus.READY
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceHigh).padding(13.dp)) {
        Text(title, color = OnBg, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = SubText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(9.dp))
        when {
            state.status == io.translatetube.local.data.ModelStatus.DOWNLOADING -> {
                Text(
                    "下载中 ${state.progress}%（${state.downloadedMB}/${state.totalMB} MB）",
                    color = BrandBright, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Brand, trackColor = SurfaceHigh,
                )
            }
            state.status == io.translatetube.local.data.ModelStatus.EXTRACTING -> {
                Text("解压安装中…", color = BrandBright, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Brand, trackColor = SurfaceHigh)
            }
            state.status == io.translatetube.local.data.ModelStatus.FAILED -> {
                Text("下载失败：${state.error ?: "网络异常"}", color = DangerRed, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    dl.download(if (title.contains("配音")) io.translatetube.local.data.ModelKind.TTS else io.translatetube.local.data.ModelKind.ASR)
                }) { Text("重试", color = BrandBright) }
            }
            isReady -> Text("●  $readyText", color = BrandBright, style = MaterialTheme.typography.bodySmall)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("尚未下载", color = SubText, style = MaterialTheme.typography.bodySmall)
                    Text("下载约 $pkgMB MB", color = SubText, style = MaterialTheme.typography.labelSmall)
                }
                Button(onClick = onDownload, shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBg)) {
                    Text("下载模型")
                }
            }
        }
    }
}

/** 统计 app 缓存占用（配音 PCM + 转写临时音频） */
private fun formatCacheSize(ctx: android.content.Context): String {
    return try {
        var bytes = 0L
        java.io.File(ctx.cacheDir, "dub").walkBottomUp().filter { it.isFile }.forEach { bytes += it.length() }
        java.io.File(ctx.cacheDir, "tts_tmp").walkBottomUp().filter { it.isFile }.forEach { bytes += it.length() }
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("asr_audio_") }?.forEach { bytes += it.length() }
        when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
            bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    } catch (_: Exception) { "未知" }
}

/** 清理配音/转写缓存 */
private fun clearAppCache(ctx: android.content.Context) {
    try {
        java.io.File(ctx.cacheDir, "dub").deleteRecursively()
        java.io.File(ctx.cacheDir, "tts_tmp").deleteRecursively()
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("asr_audio_") }?.forEach { it.delete() }
    } catch (_: Exception) {}
}
