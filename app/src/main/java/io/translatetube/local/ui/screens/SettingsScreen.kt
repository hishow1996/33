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
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = OnBg,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onBack)
                    .wrapContentSize(Alignment.Center),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("设置", style = MaterialTheme.typography.titleLarge, color = OnBg)
                Text("管理翻译、语音和应用空间", style = MaterialTheme.typography.bodySmall, color = SubText)
            }
        }
        Spacer(Modifier.height(2.dp))

        // ===== 语音模型（方案 B：软件内下载） =====
        val dlManager = remember { (ctx.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload }
        val ttsState by dlManager.tts.collectAsState()
        val asrState by dlManager.asr.collectAsState()
        // 下载状态变化会触发重组；以实际文件校验结果为准，避免“状态就绪但模型文件损坏”。
        val ttsReady = io.translatetube.local.data.ModelFiles.ttsReady(ctx)
        val asrReady = io.translatetube.local.data.ModelFiles.asrReady(ctx)
        var confirmKind by remember { mutableStateOf<io.translatetube.local.data.ModelKind?>(null) }

        Section("模型管理") {

            // —— 配音模型 ——
            ModelRow(title = "配音模型",
                state = ttsState, ready = ttsReady, pkgMB = 140,
                onDownload = { confirmKind = io.translatetube.local.data.ModelKind.TTS })
            HorizontalDivider(color = OutlineSoft, modifier = Modifier.padding(vertical = 3.dp))
            ModelRow(title = "语音转写模型",
                state = asrState, ready = asrReady, pkgMB = 198,
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

        Section("配音设置") {
            EngineOption("kokoro", "Kokoro 神经网络", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE8EDF5), thickness = 1.dp)
            EngineOption("system", "系统 TTS", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            if (ttsEngine == "kokoro") {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("中文音色", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${speaker.toInt()}", color = BrandBright, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = speaker, onValueChange = { speaker = it },
                    onValueChangeFinished = { prefs.edit().putInt("kokoro_speaker", speaker.toInt()).apply() },
                    valueRange = 0f..53f,
                    colors = SliderDefaults.colors(thumbColor = BrandBright, activeTrackColor = Brand),
                )
            }
        }

        Section("翻译设置") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onApiManager)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "翻译引擎与 API",
                    color = OnBg,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text("›", color = SubText, style = MaterialTheme.typography.headlineMedium)
            }
        }

        Section("播放体验") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("悬浮窗权限", color = OnBg, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f))
                if (canFloat) {
                    Text("已开启", color = BrandBright, style = MaterialTheme.typography.labelLarge)
                } else {
                    Button(
                        onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text("去开启") }
                }
            }
        }

        Section("存储管理") {
            var cacheSize by remember { mutableStateOf(formatCacheSize(ctx)) }
            var showClearConfirm by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("临时缓存", color = OnBg, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(3.dp))
                    Text("当前占用 $cacheSize", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright),
                ) { Text("清理缓存") }
            }
            Spacer(Modifier.height(5.dp))
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

        Section("关于应用") {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("大象翻译", color = OnBg, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text("v" + io.translatetube.local.BuildConfig.VERSION_NAME, color = SubText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = BrandBright,
        )
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = OutlineSoft, thickness = 1.dp)
    }
}

@Composable
private fun EngineOption(
    id: String, title: String,
    current: String, onPick: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = current == id,
            onClick = { onPick(id) },
            colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
        )
        Text(
            title,
            color = OnBg,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (current == id) Text("当前", color = BrandBright, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ModelRow(
    title: String,
    state: io.translatetube.local.data.ModelState, ready: Boolean, pkgMB: Int,
    onDownload: () -> Unit,
) {
    val dl = (LocalContext.current.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = OnBg, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            if (ready) {
                Text("已就绪", color = BrandBright, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(7.dp))
        when {
            ready -> {
                Text("模型文件已下载并通过检查", color = SubText, style = MaterialTheme.typography.bodySmall)
            }
            state.status == io.translatetube.local.data.ModelStatus.DOWNLOADING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        progress = { (state.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(36.dp),
                        color = BrandBright,
                        trackColor = androidx.compose.ui.graphics.Color(0xFFE5EAF0),
                        strokeWidth = 3.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "下载中 ${state.progress}% · ${state.downloadedMB}/${state.totalMB} MB",
                            color = BrandBright, style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(state.stageMessage ?: "下载完成后自动安装", color = SubText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            state.status == io.translatetube.local.data.ModelStatus.EXTRACTING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = BrandBright,
                        trackColor = androidx.compose.ui.graphics.Color(0xFFE5EAF0),
                        strokeWidth = 3.dp,
                    )
                    Text(state.stageMessage ?: "正在安装模型", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.status == io.translatetube.local.data.ModelStatus.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.error ?: "下载失败，请检查网络",
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            dl.download(if (title.contains("配音")) io.translatetube.local.data.ModelKind.TTS else io.translatetube.local.data.ModelKind.ASR)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("重试", color = BrandBright) }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("约 $pkgMB MB", color = SubText, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onDownload,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text("下载", color = BrandBright) }
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
