package io.translatetube.local.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.translatetube.local.ui.theme.*
import io.translatetube.local.translate.Crypto
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.translatetube.local.floating.FloatingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onApiManager: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var ttsEngine by remember { mutableStateOf(prefs.getString("tts_engine", "kokoro") ?: "kokoro") }
    var asrEngine by remember { mutableStateOf(prefs.getString("asr_provider", "local") ?: "local") }
    var elevenLabsKey by remember { mutableStateOf(Crypto.decrypt(prefs.getString("asr_elevenlabs_key", "").orEmpty())) }
    var cartesiaKey by remember { mutableStateOf(Crypto.decrypt(prefs.getString("asr_cartesia_key", "").orEmpty())) }
    var kawaKey by remember { mutableStateOf(Crypto.decrypt(prefs.getString("asr_kawa_key", "").orEmpty())) }
    var kawaLanguage by remember { mutableStateOf(prefs.getString("asr_kawa_language", "auto") ?: "auto") }
    var cartesiaLanguage by remember { mutableStateOf(prefs.getString("asr_cartesia_language", "en") ?: "en") }
    var asrLanguageMenuExpanded by remember { mutableStateOf(false) }
    // Cartesia documents both Norwegian Bokmål ("no") and Nynorsk ("nn"); Kawa keeps "no".
    val cartesiaLanguages = remember { listOf("en" to "英语", "zh" to "中文", "ja" to "日语", "ko" to "韩语", "es" to "西班牙语", "fr" to "法语", "de" to "德语", "pt" to "葡萄牙语", "ru" to "俄语", "it" to "意大利语", "hi" to "印地语", "ar" to "阿拉伯语", "nl" to "荷兰语", "tr" to "土耳其语", "pl" to "波兰语", "vi" to "越南语", "th" to "泰语", "id" to "印尼语", "fi" to "芬兰语", "uk" to "乌克兰语", "sv" to "瑞典语", "da" to "丹麦语", "no" to "挪威语", "nn" to "挪威语（新挪威语）") }
    val kawaLanguages = remember(cartesiaLanguages) { cartesiaLanguages.filterNot { it.first == "nn" } }
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
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp)).background(Color(0xFFF2F6FC))
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 14.dp),
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
            }
        }
        Spacer(Modifier.height(2.dp))

        // ===== 语音模型（方案 B：软件内下载） =====
        val dlManager = remember { (ctx.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload }
        val ttsState by dlManager.tts.collectAsState()
        val asrState by dlManager.asr.collectAsState()
        val playbackState by io.translatetube.local.player.PlaybackService.ui.collectAsState()
        val piperDownloadCount by io.translatetube.local.tts.PiperModels.activeDownloadCount.collectAsState()
        val modelDownloadActive = piperDownloadCount > 0 ||
            ttsState.status in setOf(io.translatetube.local.data.ModelStatus.DOWNLOADING, io.translatetube.local.data.ModelStatus.EXTRACTING) ||
            asrState.status in setOf(io.translatetube.local.data.ModelStatus.DOWNLOADING, io.translatetube.local.data.ModelStatus.EXTRACTING)
        val cacheBusy = !io.translatetube.local.data.AppCacheStoragePolicy.canClear(
            isPlaying = playbackState.isPlaying,
            isProcessing = playbackState.phase in setOf(
                io.translatetube.local.player.Phase.PARSING,
                io.translatetube.local.player.Phase.DOWNLOADING_AUDIO,
                io.translatetube.local.player.Phase.TRANSCRIBING,
                io.translatetube.local.player.Phase.TRANSLATING,
                io.translatetube.local.player.Phase.SYNTHESIZING,
            ),
            dubbingIncomplete = playbackState.phase == io.translatetube.local.player.Phase.READY &&
                playbackState.totalLines > 0 &&
                playbackState.preparedCount < playbackState.totalLines,
            modelDownloadActive = modelDownloadActive,
        )
        // 下载状态变化会触发重组；以实际文件校验结果为准，避免“状态就绪但模型文件损坏”。
        val ttsReady = io.translatetube.local.data.ModelFiles.ttsReady(ctx)
        val asrReady = io.translatetube.local.data.ModelFiles.asrReady(ctx)
        var confirmKind by remember { mutableStateOf<io.translatetube.local.data.ModelKind?>(null) }









Section("翻译引擎", onHeaderClick = onApiManager) {}

Section("转写引擎") {
    Text(
        "没有现成字幕时使用。云端模式只上传提取后的音频，不上传视频画面。",
        color = SubText,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsrProviderCard(
            title = "本地 Whisper",
            subtitle = "离线 · 无需密钥",
            selected = asrEngine == "local",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "local"
                prefs.edit().putString("asr_provider", "local").apply()
            },
        )
        AsrProviderCard(
            title = "ElevenLabs",
            subtitle = "自动检测语种",
            selected = asrEngine == "elevenlabs",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "elevenlabs"
                prefs.edit().putString("asr_provider", "elevenlabs").apply()
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsrProviderCard(
            title = "Cartesia Ink",
            subtitle = "需指定原声语言",
            selected = asrEngine == "cartesia",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "cartesia"
                prefs.edit().putString("asr_provider", "cartesia").apply()
            },
        )
        AsrProviderCard(
            title = "Kawa",
            subtitle = "每月 1,000 分钟免费",
            selected = asrEngine == "kawa",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "kawa"
                prefs.edit().putString("asr_provider", "kawa").apply()
            },
        )
    }

    Spacer(Modifier.height(12.dp))
    if (asrEngine == "local") {
        Text(
            "本地识别不需要 API Key。请先在模型管理中下载 Whisper 模型。",
            color = SubText,
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        val selectedApiKey = when (asrEngine) {
            "elevenlabs" -> elevenLabsKey
            "cartesia" -> cartesiaKey
            else -> kawaKey
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFF7F9FC),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("服务配置", color = OnBg, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (asrEngine) {
                        "elevenlabs" -> "使用 Scribe 云端识别，语言自动检测。"
                        "cartesia" -> "填写 API Key，并选择视频原声语言。"
                        else -> "每月免费额度及实际计费以 Kawa 账户显示为准。"
                    },
                    color = SubText,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = selectedApiKey,
                    onValueChange = { value ->
                        when (asrEngine) {
                            "elevenlabs" -> {
                                elevenLabsKey = value
                                prefs.edit().putString("asr_elevenlabs_key", Crypto.encrypt(value.trim())).apply()
                            }
                            "cartesia" -> {
                                cartesiaKey = value
                                prefs.edit().putString("asr_cartesia_key", Crypto.encrypt(value.trim())).apply()
                            }
                            else -> {
                                kawaKey = value
                                prefs.edit().putString("asr_kawa_key", Crypto.encrypt(value.trim())).apply()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API Key") },
                    placeholder = { Text("粘贴服务商 API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    if (selectedApiKey.isBlank()) "尚未填写密钥" else "密钥已加密保存在本机",
                    color = if (selectedApiKey.isBlank()) SubText else BrandBright,
                    style = MaterialTheme.typography.labelSmall,
                )

                if (asrEngine == "cartesia" || asrEngine == "kawa") {
                    val selectedLanguage = if (asrEngine == "cartesia") cartesiaLanguage else kawaLanguage
                    val languageOptions = if (asrEngine == "cartesia") cartesiaLanguages else kawaLanguages
                    Text("识别语言", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                    Box {
                        OutlinedButton(onClick = { asrLanguageMenuExpanded = true }) {
                            Text(
                                if (asrEngine == "kawa" && selectedLanguage.equals("auto", ignoreCase = true)) "自动检测"
                                else languageOptions.firstOrNull { it.first == selectedLanguage }?.second ?: "英语"
                            )
                            Icon(Icons.Filled.ExpandMore, contentDescription = "选择识别语言")
                        }
                        DropdownMenu(
                            expanded = asrLanguageMenuExpanded,
                            onDismissRequest = { asrLanguageMenuExpanded = false },
                        ) {
                            if (asrEngine == "kawa") {
                                DropdownMenuItem(
                                    text = { Text("自动检测") },
                                    onClick = {
                                        kawaLanguage = "auto"
                                        prefs.edit().putString("asr_kawa_language", "auto").apply()
                                        asrLanguageMenuExpanded = false
                                    },
                                )
                            }
                            languageOptions.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        if (asrEngine == "cartesia") {
                                            cartesiaLanguage = code
                                            prefs.edit().putString("asr_cartesia_language", code).apply()
                                        } else {
                                            kawaLanguage = code
                                            prefs.edit().putString("asr_kawa_language", code).apply()
                                        }
                                        asrLanguageMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            "提示：云端服务需要网络，识别额度与超额收费由服务商决定。",
            color = SubText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 2.dp, top = 5.dp),
        )
    }
}

Section("配音设置") {
            EngineOption("kokoro", "Kokoro 神经网络", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE8EDF5), thickness = 1.dp)
            EngineOption("piper", "Piper 多语种 · 按语种下载", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE8EDF5), thickness = 1.dp)
            EngineOption("system", "系统 TTS · 无需下载", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            Text(
                when (ttsEngine) {
                    "system" -> "直接使用手机已安装的语音；不会要求下载 Kokoro 配音模型。"
                    "piper" -> "Piper 补足 Kokoro 覆盖不到的语种（法/德/西/俄/意等）；请在下方模型管理中下载对应语种模型。"
                    else -> "使用本地 Kokoro 模型生成配音；首次使用前需下载模型。"
                },
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
            if (ttsEngine == "kokoro") {
                Spacer(Modifier.height(8.dp))
                var previewing by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音色（0–53 · 覆盖中/英/日等多语种）", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${speaker.toInt()}", color = BrandBright, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = speaker, onValueChange = { speaker = it },
                    onValueChangeFinished = { prefs.edit().putInt("kokoro_speaker", speaker.toInt()).apply() },
                    valueRange = 0f..53f,
                    colors = SliderDefaults.colors(thumbColor = BrandBright, activeTrackColor = Brand),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "不同编号对应不同语种与音色，拖动后试听确定",
                        color = SubText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = ttsReady && !previewing,
                        onClick = { previewing = true },
                    ) { Text(if (previewing) "合成中…" else "试听") }
                }
                if (previewing) {
                    KokoroVoicePreview(
                        speaker = speaker.toInt(),
                        onDone = { previewing = false },
                    )
                }
            }
            if (ttsEngine == "piper") {
                PiperModelSection()
            }
        }

Section("视频源（yt-dlp）") {
    YtDlpUpdateSection()
}

Section("模型管理") {
            Text(
                when {
                    asrEngine != "local" -> "当前选用云端转写；本地 Whisper 模型仍保留，切回本地时即可继续使用。"
                    ttsEngine == "system" -> "系统 TTS 不需要配音模型；本地语音转写模型可单独下载。"
                    else -> "配音模型用于离线合成语音，本地语音转写模型用于生成字幕。"
                },
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (ttsEngine == "kokoro") {
                ModelRow(
                    title = "配音模型",
                    state = ttsState,
                    ready = ttsReady,
                    pkgMB = 140,
                    onDownload = { confirmKind = io.translatetube.local.data.ModelKind.TTS },
                )
                HorizontalDivider(color = OutlineSoft, modifier = Modifier.padding(vertical = 3.dp))
            }
            ModelRow(
                title = "语音转写模型",
                state = asrState,
                ready = asrReady,
                pkgMB = 198,
                onDownload = { confirmKind = io.translatetube.local.data.ModelKind.ASR },
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
            var cacheClearMessage by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("临时缓存", color = OnBg, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(3.dp))
                    Text("当前占用 $cacheSize", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    enabled = !cacheBusy,
                    onClick = {
                        cacheClearMessage = null
                        showClearConfirm = true
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright),
                ) { Text("清理缓存") }
            }
            if (cacheBusy) {
                Text(
                    "视频播放、转写、配音或语音模型下载处理中，暂不能清理缓存，以免中断当前任务。",
                    color = SubText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            cacheClearMessage?.let {
                Text(it, color = SubText, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(5.dp))
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("清理临时缓存？", color = OnBg) },
                    text = {
                        Text(
                            if (cacheBusy) "当前仍有播放、处理或模型下载任务使用这些缓存。请先等待任务结束，再清理缓存。"
                            else "将清理配音 PCM、系统 TTS 临时 WAV、转写音频和 HLS 分段缓存。历史记录、字幕缓存和已下载模型不会删除。",
                            color = SubText,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !cacheBusy,
                            onClick = {
                                if (!cacheBusy) {
                                    val cleared = clearAppCache(ctx)
                                    cacheSize = formatCacheSize(ctx)
                                    cacheClearMessage = if (cleared) {
                                        "临时缓存已清理。"
                                    } else {
                                        "部分缓存仍被占用，未能完全清理；请稍后重试。"
                                    }
                                    showClearConfirm = false
                                }
                            },
                        ) { Text("确认清理", color = if (cacheBusy) SubText else DangerRed) }
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
private fun Section(
    title: String,
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val glyph = when (title) {
        "翻译引擎", "翻译设置" -> Icons.Filled.Translate
        "配音设置" -> Icons.Filled.VolumeUp
        "模型管理" -> Icons.Filled.Download
        "播放体验" -> Icons.Filled.FolderOpen
        "存储管理" -> Icons.Filled.FolderOpen
        else -> Icons.Filled.Info
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F3F8)),
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        if (onHeaderClick != null) onHeaderClick() else expanded = !expanded
                    }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = glyph,
                        contentDescription = null,
                        tint = BrandBright,
                        modifier = Modifier.size(25.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = OnBg, style = MaterialTheme.typography.titleLarge)
                }
                if (onHeaderClick != null) {
                    Text("＞", color = SubText, style = MaterialTheme.typography.titleLarge)
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起设置项" else "展开设置项",
                        tint = SubText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (expanded && onHeaderClick == null) {
                HorizontalDivider(color = OutlineSoft)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Kokoro 音色试听：按当前音色编号合成一句中英混合短句并播放。
 * 每次试听独立实例化引擎（模型加载需数秒），结束后释放。
 */
@Composable
private fun KokoroVoicePreview(speaker: Int, onDone: () -> Unit) {
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(speaker) {
        withContext(Dispatchers.IO) {
            var tts: io.translatetube.local.tts.KokoroTts? = null
            var track: android.media.AudioTrack? = null
            try {
                tts = io.translatetube.local.tts.KokoroTts(appCtx, speakerId = speaker)
                val audio = tts.synth("你好，这是配音音色试听。Hello, this is a voice preview.")
                val minBuf = android.media.AudioTrack.getMinBufferSize(
                    audio.sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT,
                )
                track = android.media.AudioTrack(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    android.media.AudioFormat.Builder()
                        .setSampleRate(audio.sampleRate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    maxOf(minBuf, audio.samples.size * 4),
                    android.media.AudioTrack.MODE_STATIC,
                    0, // AUDIO_SESSION_ID_GENERATE
                )
                track.write(audio.samples, 0, audio.samples.size, android.media.AudioTrack.WRITE_BLOCKING)
                track.play()
                val playMs = audio.samples.size * 1000L / audio.sampleRate
                Thread.sleep(playMs + 150)
            } catch (e: Exception) {
                android.util.Log.w("SettingsScreen", "音色试听失败", e)
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                try { tts?.close() } catch (_: Exception) {}
            }
        }
        onDone()
    }
}

/** Piper 多语种模型管理：下载状态 + 语种清单 + 使用中标记。 */
@Composable
private fun PiperModelSection() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var selected by remember {
        mutableStateOf(
            prefs.getString("piper_model", null)
                ?.takeIf { io.translatetube.local.tts.PiperModels.byId(it)?.supportsChineseOutput == true }
                ?: "zh_cn_chaowen_medium"
        )
    }
    val installed by io.translatetube.local.tts.PiperModels.installed.collectAsState()
    val scope = rememberCoroutineScope()
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (prefs.getString("piper_model", null) != selected) {
            prefs.edit().putString("piper_model", selected).apply()
        }
        io.translatetube.local.tts.PiperModels.refreshInstalled(ctx.applicationContext)
    }

    Text(
        "当前翻译目标为中文，请下载中文 Piper 模型；其他语种模型暂不能用于中文配音。",
        color = SubText, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
    error?.let {
        Text(
            it, color = Color(0xFFC0392B),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    io.translatetube.local.tts.PiperModels.catalog.filter { it.supportsChineseOutput }.forEach { def ->
        val isInstalled = installed.contains(def.id)
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = isInstalled && def.supportsChineseOutput) {
                    selected = def.id
                    prefs.edit().putString("piper_model", def.id).apply()
                }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected == def.id,
                onClick = {
                    if (isInstalled) {
                        selected = def.id
                        prefs.edit().putString("piper_model", def.id).apply()
                    }
                },
                enabled = isInstalled && def.supportsChineseOutput,
                colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
            )
            Column(Modifier.weight(1f)) {
                Text("${def.langLabel} · ${def.voiceLabel} · 约 ${def.sizeMB}MB", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                when {
                    busyId == def.id -> {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            color = BrandBright,
                        )
                    }
                    isInstalled -> Text("已就绪", color = BrandBright, style = MaterialTheme.typography.labelSmall)
                    else -> Text("未下载", color = SubText, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!isInstalled && busyId == null) {
                TextButton(onClick = {
                    busyId = def.id
                    progress = 0f
                    error = null
                    scope.launch {
                        try {
                            io.translatetube.local.tts.PiperModels.download(
                                ctx.applicationContext,
                                def.id,
                                onProgress = { p -> progress = p / 100f },
                            )
                        } catch (e: Exception) {
                            error = "${def.langLabel}下载失败：${e.message ?: "未知错误"}"
                        } finally {
                            busyId = null
                        }
                    }
                }) { Text("下载") }
            }
        }
        HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE8EDF5), thickness = 1.dp)
    }
}

/** yt-dlp 解析规则版本管理：自动检测（上午/下午/晚上各一次）+ 应用内更新。 */
@Composable
private fun YtDlpUpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by io.translatetube.local.yt.YtDlpRepo.updateState.collectAsState()
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 进入设置页时补一次检查：时段内去重回填状态，跨时段则真查
    LaunchedEffect(Unit) {
        runCatching { io.translatetube.local.yt.YtDlpRepo.checkForUpdate(ctx.applicationContext) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("解析规则版本", color = OnBg, style = MaterialTheme.typography.bodyMedium)
            val desc = when {
                state.current == null -> "尚未使用过 yt-dlp；首次解析网页视频后开启检测"
                state.hasUpdate && state.latest != null ->
                    "发现新版本 ${state.latest}（当前 ${state.current}）"
                state.latest != null -> "当前 ${state.current} · 已是最新"
                else -> "当前 ${state.current}"
            }
            Text(desc, color = if (state.hasUpdate) BrandBright else SubText, style = MaterialTheme.typography.bodySmall)
            if (state.hasUpdate) {
                Text(
                    "网站改版后旧规则可能解析失败，建议更新（约 30MB）",
                    color = SubText, style = MaterialTheme.typography.bodySmall,
                )
            }
            message?.let {
                Text(it, color = BrandBright, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.hasUpdate) {
            TextButton(
                enabled = !updating,
                onClick = {
                    updating = true
                    message = null
                    scope.launch {
                        try {
                            message = io.translatetube.local.yt.YtDlpRepo.updateRules(ctx.applicationContext)
                        } catch (e: Exception) {
                            message = "更新失败：${e.message ?: "网络错误"}"
                        } finally {
                            updating = false
                        }
                    }
                },
            ) { Text(if (updating) "更新中…" else "立即更新") }
        } else {
            TextButton(
                enabled = !state.checking,
                onClick = {
                    scope.launch {
                        runCatching {
                            io.translatetube.local.yt.YtDlpRepo.checkForUpdate(ctx.applicationContext, force = true)
                        }
                    }
                },
            ) { Text(if (state.checking) "检查中…" else "检查更新") }
        }
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
private fun AsrProviderCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        color = if (selected) Color(0xFFEAF2FF) else Color.White,
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) BrandBright else OutlineSoft,
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = OnBg,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text("✓", color = BrandBright, style = MaterialTheme.typography.titleSmall)
                }
            }
            Text(
                subtitle,
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
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
        val bytes = io.translatetube.local.data.AppCacheStoragePolicy.sizeBytes(ctx.cacheDir)
        when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
            bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    } catch (_: Exception) { "未知" }
}

/** 清理配音/转写缓存；失败时返回 false，让设置页向用户说明。 */
private fun clearAppCache(ctx: android.content.Context): Boolean =
    io.translatetube.local.data.AppCacheStoragePolicy.clear(ctx.cacheDir)
