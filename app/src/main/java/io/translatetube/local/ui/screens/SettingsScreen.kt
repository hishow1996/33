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
    var canFloat by remember { mutableStateOf(FloatingManager(ctx).canDraw()) }

    Column(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 返回", color = OnBg) }
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = OnBg)
        Spacer(Modifier.height(16.dp))

        // ===== 语音模型（方案 B：软件内下载） =====
        val dlManager = remember { (ctx.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload }
        val ttsState by dlManager.tts.collectAsState()
        val asrState by dlManager.asr.collectAsState()
        var ttsReady by remember { mutableStateOf(io.translatetube.local.data.ModelFiles.ttsReady(ctx)) }
        var asrReady by remember { mutableStateOf(io.translatetube.local.data.ModelFiles.asrReady(ctx)) }
        var confirmKind by remember { mutableStateOf<io.translatetube.local.data.ModelKind?>(null) }

        Section("语音模型") {
            Text("配音与无字幕转写需要对应模型，各自独立下载（Wi-Fi 建议）。", color = MutedGreen, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))

            // —— 配音模型 ——
            ModelRow(
                title = "🎙 配音模型（Kokoro v1.1）",
                state = ttsState,
                ready = ttsReady,
                pkgMB = 140,
                readyText = "已就绪 · 中文配音可用",
            )
            Spacer(Modifier.height(10.dp))
            // —— 转写模型 ——
            ModelRow(
                title = "📝 转写模型（Whisper base）",
                state = asrState,
                ready = asrReady,
                pkgMB = 198,
                readyText = "已就绪 · 无字幕视频可转写",
            )
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

        Section("翻译源") {
            TextButton(onClick = onApiManager) { Text("管理云翻译 API（OpenAI/Gemini/谷歌/Azure）→", color = BrandBright) }
            Text("默认使用本地离线引擎。添加 API 后自动优先使用（失败会回退本地）。", color = SubText, style = MaterialTheme.typography.bodySmall)
        }

        Section("悬浮窗") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (canFloat) "✅ 已授权" else "⚠️ 未授权", color = if (canFloat) BrandBright else WarnYellow, modifier = Modifier.weight(1f))
                if (!canFloat) {
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
                    }, colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text("去授权") }
                }
            }
            Text("悬浮窗播放需要系统「显示在其他应用上层」权限。", color = SubText, style = MaterialTheme.typography.bodySmall)
        }

        Section("语音转写（无字幕视频）") {
            Text("Whisper base 多语言模型已内置（约 160MB）。无字幕视频会自动转写，速度约为实时的 0.5-1 倍。", color = SubText, style = MaterialTheme.typography.bodySmall)
        }

        Section("存储") {
            var cacheSize by remember { mutableStateOf("") }
            var cacheCleared by remember { mutableStateOf(false) }
            LaunchedEffect(cacheCleared) {
                cacheSize = formatCacheSize(ctx)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("配音/字幕缓存：$cacheSize", color = OnBg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        clearAppCache(ctx)
                        cacheCleared = !cacheCleared
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBg),
                ) { Text("清理") }
            }
            Text("清理后已配音的视频再次观看时需要重新合成；不影响观看历史与收藏。", color = SubText, style = MaterialTheme.typography.bodySmall)
        }

        Section("关于") {
            Text("大象翻译 v1.0.2", color = OnBg, style = MaterialTheme.typography.bodyMedium)
            Text("离线翻译 · 本地神经配音 · 悬浮窗 · 无订阅", color = MutedGreen, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceDark)
            .padding(14.dp).padding(bottom = 6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = BrandBright)
        Spacer(Modifier.height(8.dp))
        content()
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EngineOption(
    id: String, title: String, desc: String,
    current: String, onPick: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable { onPick(id) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == id, onClick = { onPick(id) }, colors = RadioButtonDefaults.colors(selectedColor = BrandBright))
        Column {
            Text(title, color = OnBg, style = MaterialTheme.typography.bodyLarge)
            Text(desc, color = SubText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    state: io.translatetube.local.data.ModelState,
    ready: Boolean,
    pkgMB: Int,
    readyText: String,
) {
    val dl = (LocalContext.current.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceDark).padding(12.dp)) {
        Text(title, color = OnBg, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
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
            ready -> Text("✅ $readyText", color = BrandBright, style = MaterialTheme.typography.bodySmall)
            else -> Text("未下载 · 压缩包约 $pkgMB MB", color = SubText, style = MaterialTheme.typography.bodySmall)
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
