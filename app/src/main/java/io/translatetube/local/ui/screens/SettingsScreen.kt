package io.translatetube.local.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.translatetube.local.data.AppDatabase
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
    var systemTtsMode by remember { mutableStateOf(prefs.getString("system_tts_mode", "generated") ?: "generated") }
    var systemTtsVoiceName by remember { mutableStateOf(prefs.getString("system_tts_voice_name", "").orEmpty()) }
    var systemTtsVoiceCatalog by remember {
        mutableStateOf(
            io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.decodeVoiceCatalog(
                prefs.getString(io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY, null),
            ),
        )
    }
    var systemTtsVoiceMenuExpanded by remember { mutableStateOf(false) }
    var refreshingSystemTtsVoices by remember { mutableStateOf(false) }
    var systemTtsVoiceStatus by remember { mutableStateOf<String?>(null) }
    val systemTtsSettingsScope = androidx.compose.runtime.rememberCoroutineScope()
    var asrEngine by remember { mutableStateOf(prefs.getString("asr_provider", "local") ?: "local") }
    var localAsrModel by remember {
        mutableStateOf(io.translatetube.local.data.ModelFiles.selectedLocalAsrModelId(ctx))
    }
    var elevenLabsKey by remember { mutableStateOf(Crypto.decrypt(prefs.getString("asr_elevenlabs_key", "").orEmpty())) }
    var cartesiaKey by remember { mutableStateOf(Crypto.decrypt(prefs.getString("asr_cartesia_key", "").orEmpty())) }
    var kawaKey by remember { mutableStateOf(Crypto.decrypt(prefs.getString("asr_kawa_key", "").orEmpty())) }
    var kawaLanguage by remember { mutableStateOf(prefs.getString("asr_kawa_language", "auto") ?: "auto") }
    var cartesiaLanguage by remember { mutableStateOf(prefs.getString("asr_cartesia_language", "en") ?: "en") }
    var asrLanguageMenuExpanded by remember { mutableStateOf(false) }
    var asrConfigExpanded by remember { mutableStateOf(false) }
    var asrConfigSavedMessage by remember { mutableStateOf<String?>(null) }
    // Cartesia documents both Norwegian Bokmål ("no") and Nynorsk ("nn"); Kawa keeps "no".
    val cartesiaLanguages = remember { listOf("en" to "英语", "zh" to "中文", "ja" to "日语", "ko" to "韩语", "es" to "西班牙语", "fr" to "法语", "de" to "德语", "pt" to "葡萄牙语", "ru" to "俄语", "it" to "意大利语", "hi" to "印地语", "ar" to "阿拉伯语", "nl" to "荷兰语", "tr" to "土耳其语", "pl" to "波兰语", "vi" to "越南语", "th" to "泰语", "id" to "印尼语", "fi" to "芬兰语", "uk" to "乌克兰语", "sv" to "瑞典语", "da" to "丹麦语", "no" to "挪威语", "nn" to "挪威语（新挪威语）") }
    val kawaLanguages = remember(cartesiaLanguages) { cartesiaLanguages.filterNot { it.first == "nn" } }
    var speaker by remember { mutableStateOf(prefs.getInt("kokoro_speaker", 14).toFloat()) }
    fun refreshSystemTtsVoices() {
        if (refreshingSystemTtsVoices) return
        refreshingSystemTtsVoices = true
        systemTtsVoiceStatus = "正在读取手机系统提供的中文音色…"
        systemTtsSettingsScope.launch {
            try {
                val voices = withContext(Dispatchers.IO) {
                    var engine: io.translatetube.local.tts.SystemTts? = null
                    try {
                        engine = io.translatetube.local.tts.SystemTts(ctx.applicationContext)
                        engine.chineseVoiceNames
                    } finally {
                        runCatching { engine?.close() }
                    }
                }
                if (voices.isEmpty()) {
                    systemTtsVoiceStatus = "系统没有返回可用的中文音色，请检查手机的文字转语音设置。"
                } else {
                    systemTtsVoiceCatalog = voices
                    val selected = systemTtsVoiceName.takeIf { it in voices } ?: voices.first()
                    systemTtsVoiceName = selected
                    prefs.edit()
                        .putString("system_tts_voice_name", selected)
                        .putString(
                            io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY,
                            io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy.encodeVoiceCatalog(voices),
                        )
                        .apply()
                    systemTtsVoiceStatus = "已读取 ${voices.size} 个系统中文音色；选择会在下次打开视频时生效。"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                systemTtsVoiceStatus = "读取系统音色失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                refreshingSystemTtsVoices = false
            }
        }
    }
    LaunchedEffect(ttsEngine) {
        if (ttsEngine == "system") {
            // Refresh even when an older app version stored a filtered voice catalogue.
            refreshSystemTtsVoices()
        }
    }
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
        Modifier.fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFF3F6FC))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = OnBg, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("设置中心", style = MaterialTheme.typography.titleLarge, color = OnBg)
                Text("个性化你的翻译体验", style = MaterialTheme.typography.bodySmall, color = SubText)
            }
        }

        // ===== 语音模型（方案 B：软件内下载） =====
        val dlManager = remember { (ctx.applicationContext as io.translatetube.local.TranslateTubeApp).modelDownload }
        val ttsState by dlManager.tts.collectAsState()
        val asrState by dlManager.asr.collectAsState()
        val playbackState by io.translatetube.local.player.PlaybackService.ui.collectAsState()
        val piperDownloadCount by io.translatetube.local.tts.PiperModels.activeDownloadCount.collectAsState()
        val piperTaskActive = io.translatetube.local.tts.PiperModels.hasActiveDownloads
        val modelDownloadActive = piperTaskActive || piperDownloadCount > 0 ||
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










        val ttsLabel = when (ttsEngine) {
            "system" -> "系统 TTS"
            "piper" -> "Piper 多语种"
            else -> "Kokoro 神经网络"
        }
        val asrLabel = when (asrEngine) {
            "elevenlabs" -> "ElevenLabs"
            "cartesia" -> "Cartesia Ink"
            "kawa" -> "Kawa 云端识别"
            else -> if (localAsrModel == "sensevoice") "本地 SenseVoice" else "本地 Whisper"
        }
        val apiProfiles by AppDatabase.get(ctx).apiProfileDao().all()
            .collectAsState(initial = emptyList())
        val activeApiId = prefs.getLong("active_api_id", -1L)
        val translationLabel = apiProfiles
            .firstOrNull { it.id == activeApiId && it.enabled }
            ?.let { profile ->
                listOf(profile.name.trim(), profile.model.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            }
            ?.takeIf { it.isNotBlank() }
            ?: "Google 翻译"

        // Settings overview: surface the active engines before the detailed sections.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF244DB5), Color(0xFF3978E8), Color(0xFF5A9BFA)),
                    ),
                )
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("配音工作台", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.14f)).padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.VolumeUp,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("配音引擎", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(ttsLabel, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.14f)).padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Translate,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("转写方式", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(asrLabel, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Translate,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("翻译引擎", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        translationLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

Section("翻译引擎", onHeaderClick = onApiManager) {}

Section("转写引擎") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsrProviderCard(
            title = "本地转写",
            selected = asrEngine == "local",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "local"
                asrConfigExpanded = false
                asrConfigSavedMessage = null
                prefs.edit().putString("asr_provider", "local").apply()
            },
        )
        AsrProviderCard(
            title = "ElevenLabs",
            onWebsiteClick = { openOfficialWebsite(ctx, "https://elevenlabs.io/speech-to-text") },
            selected = asrEngine == "elevenlabs",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "elevenlabs"
                asrConfigExpanded = false
                asrConfigSavedMessage = null
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
            onWebsiteClick = { openOfficialWebsite(ctx, "https://www.cartesia.ai/speech-to-text") },
            selected = asrEngine == "cartesia",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "cartesia"
                asrConfigExpanded = false
                asrConfigSavedMessage = null
                prefs.edit().putString("asr_provider", "cartesia").apply()
            },
        )
        AsrProviderCard(
            title = "Kawa",
            onWebsiteClick = { openOfficialWebsite(ctx, "https://docs.makimoto.ai/service/api-reference/") },
            selected = asrEngine == "kawa",
            modifier = Modifier.weight(1f),
            onClick = {
                asrEngine = "kawa"
                asrConfigExpanded = false
                asrConfigSavedMessage = null
                prefs.edit().putString("asr_provider", "kawa").apply()
            },
        )
    }

    Spacer(Modifier.height(12.dp))
    if (asrEngine == "local") {
        val asrModelBusy = asrState.status in setOf(
            io.translatetube.local.data.ModelStatus.DOWNLOADING,
            io.translatetube.local.data.ModelStatus.EXTRACTING,
        )
        Text("本地转写模型", color = OnBg, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AsrProviderCard(
                title = "Whisper Base",
                selected = localAsrModel == "whisper",
                enabled = !asrModelBusy,
                modifier = Modifier.weight(1f),
                onClick = {
                    localAsrModel = "whisper"
                    prefs.edit().putString("asr_local_model", "whisper").apply()
                    dlManager.refreshAsrState()
                },
            )
            AsrProviderCard(
                title = "SenseVoice INT8",
                selected = localAsrModel == "sensevoice",
                enabled = !asrModelBusy,
                modifier = Modifier.weight(1f),
                onClick = {
                    localAsrModel = "sensevoice"
                    prefs.edit().putString("asr_local_model", "sensevoice").apply()
                    dlManager.refreshAsrState()
                },
            )
        }
        if (localAsrModel == "sensevoice") {
            Text(
                "支持普通话、粤语、英语、日语和韩语；模型还可返回语音情绪与声音事件标签。",
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ModelRow(
            title = if (localAsrModel == "sensevoice") "SenseVoice Small INT8 转写模型" else "Whisper Base 转写模型",
            state = asrState,
            ready = asrReady,
            pkgMB = if (localAsrModel == "sensevoice") 158 else 198,
            onDownload = { dlManager.download(io.translatetube.local.data.ModelKind.ASR) },
            onCancel = { dlManager.cancel(io.translatetube.local.data.ModelKind.ASR) },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { asrConfigExpanded = !asrConfigExpanded }
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("服务配置", color = OnBg, style = MaterialTheme.typography.titleMedium)
                    }
                    Icon(
                        imageVector = if (asrConfigExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (asrConfigExpanded) "收起服务配置" else "展开服务配置",
                        tint = BrandBright,
                    )
                }
                if (asrConfigExpanded) {
                    OutlinedTextField(
                        value = selectedApiKey,
                        onValueChange = { value ->
                            asrConfigSavedMessage = null
                            when (asrEngine) {
                                "elevenlabs" -> elevenLabsKey = value
                                "cartesia" -> cartesiaKey = value
                                else -> kawaKey = value
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API Key") },
                        placeholder = { Text("粘贴服务商 API Key") },
                        visualTransformation = PasswordVisualTransformation(),
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
                                            asrConfigSavedMessage = null
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
                                            } else {
                                                kawaLanguage = code
                                            }
                                            asrConfigSavedMessage = null
                                            asrLanguageMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        asrConfigSavedMessage?.let {
                            Text(
                                it,
                                color = BrandBright,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Button(
                            onClick = {
                                when (asrEngine) {
                                    "elevenlabs" -> prefs.edit()
                                        .putString("asr_elevenlabs_key", Crypto.encrypt(elevenLabsKey.trim()))
                                        .apply()
                                    "cartesia" -> prefs.edit()
                                        .putString("asr_cartesia_key", Crypto.encrypt(cartesiaKey.trim()))
                                        .putString("asr_cartesia_language", cartesiaLanguage)
                                        .apply()
                                    else -> prefs.edit()
                                        .putString("asr_kawa_key", Crypto.encrypt(kawaKey.trim()))
                                        .putString("asr_kawa_language", kawaLanguage)
                                        .apply()
                                }
                                asrConfigSavedMessage = "配置已保存"
                            },
                            enabled = selectedApiKey.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("保存配置")
                        }
                    }
                }
            }
        }
    }
}

Section("配音设置") {
            EngineOption("kokoro", "Kokoro 神经网络", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            EngineOption("piper", "Piper 多语种 · 按语种下载", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            EngineOption("system", "系统 TTS · 无需下载", ttsEngine) { e ->
                ttsEngine = e
                prefs.edit().putString("tts_engine", e).apply()
            }
            if (ttsEngine == "system") {
                HorizontalDivider(color = OutlineSoft, modifier = Modifier.padding(vertical = 3.dp))
                Text("系统 TTS 配音模式", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .clickable {
                            systemTtsMode = "generated"
                            prefs.edit().putString("system_tts_mode", systemTtsMode).apply()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = systemTtsMode == "generated",
                        onClick = {
                            systemTtsMode = "generated"
                            prefs.edit().putString("system_tts_mode", systemTtsMode).apply()
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("生成合成配音（边合成边播）", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                        Text("先准备前面的语音缓冲，再边播放边生成后续字幕", color = SubText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .clickable {
                            systemTtsMode = "instant"
                            prefs.edit().putString("system_tts_mode", systemTtsMode).apply()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = systemTtsMode == "instant",
                        onClick = {
                            systemTtsMode = "instant"
                            prefs.edit().putString("system_tts_mode", systemTtsMode).apply()
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("即时朗读配音", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                        Text("在字幕时间点调用系统 TTS 直接朗读，不先生成 WAV/PCM 文件", color = SubText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("即时朗读不需要等待整段配音合成，但系统语音服务仍可能有启动延迟；模式和音色将在下次打开视频时生效。", color = SubText, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = OutlineSoft, modifier = Modifier.padding(vertical = 3.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("系统中文音色", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            systemTtsVoiceName.ifBlank { "尚未选择；请读取系统音色" },
                            color = SubText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                    Box {
                        TextButton(
                            onClick = { systemTtsVoiceMenuExpanded = true },
                            enabled = systemTtsVoiceCatalog.isNotEmpty(),
                        ) { Text("选择音色") }
                        DropdownMenu(
                            expanded = systemTtsVoiceMenuExpanded,
                            onDismissRequest = { systemTtsVoiceMenuExpanded = false },
                        ) {
                            systemTtsVoiceCatalog.forEach { voiceName ->
                                DropdownMenuItem(
                                    text = { Text(voiceName, maxLines = 2) },
                                    onClick = {
                                        systemTtsVoiceName = voiceName
                                        prefs.edit().putString("system_tts_voice_name", voiceName).apply()
                                        systemTtsVoiceMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { refreshSystemTtsVoices() },
                        enabled = !refreshingSystemTtsVoices,
                    ) { Text(if (refreshingSystemTtsVoices) "读取中…" else "读取音色") }
                }
                systemTtsVoiceStatus?.let {
                    Text(it, color = SubText, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ttsEngine == "kokoro") {
                ModelRow(
                    title = "Kokoro 配音模型",
                    state = ttsState,
                    ready = ttsReady,
                    pkgMB = 140,
                    onDownload = { dlManager.download(io.translatetube.local.data.ModelKind.TTS) },
                    onCancel = { dlManager.cancel(io.translatetube.local.data.ModelKind.TTS) },
                )
                HorizontalDivider(color = OutlineSoft, modifier = Modifier.padding(vertical = 3.dp))
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
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
            // Cache trees can contain large audio segments. Never scan them synchronously during
            // composition; the first frame should remain responsive even when the cache is large.
            var cacheSize by remember { mutableStateOf("计算中…") }
            var showClearConfirm by remember { mutableStateOf(false) }
            var cacheClearMessage by remember { mutableStateOf<String?>(null) }
            var cacheClearing by remember { mutableStateOf(false) }
            val cacheScope = rememberCoroutineScope()
            LaunchedEffect(ctx) {
                cacheSize = withContext(Dispatchers.IO) { formatCacheSize(ctx) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("临时缓存", color = OnBg, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(3.dp))
                    Text("当前占用 $cacheSize", color = SubText, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    enabled = !cacheBusy && !cacheClearing,
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
            } else if (cacheClearing) {
                Text("正在清理临时缓存…", color = SubText, style = MaterialTheme.typography.bodySmall)
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
                            enabled = !cacheBusy && !cacheClearing,
                            onClick = {
                                if (!cacheBusy && !cacheClearing) {
                                    showClearConfirm = false
                                    cacheClearing = true
                                    cacheClearMessage = null
                                    cacheScope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                val cleared = clearAppCache(ctx)
                                                cleared to formatCacheSize(ctx)
                                            }
                                            cacheSize = result.second
                                            cacheClearMessage = if (result.first) {
                                                "临时缓存已清理。"
                                            } else {
                                                "部分缓存仍被占用，未能完全清理；请稍后重试。"
                                            }
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            android.util.Log.w("SettingsScreen", "清理临时缓存失败", error)
                                            cacheClearMessage = "清理缓存失败；请检查存储空间后重试。"
                                        } finally {
                                            cacheClearing = false
                                        }
                                    }
                                }
                            },
                        ) { Text(if (cacheClearing) "清理中…" else "确认清理", color = if (cacheBusy || cacheClearing) SubText else DangerRed) }
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
        "模型管理", "视频源（yt-dlp）" -> Icons.Filled.Download
        "播放体验", "存储管理" -> Icons.Filled.FolderOpen
        else -> Icons.Filled.Info
    }
    val subtitle = when (title) {
        "翻译引擎", "翻译设置" -> "API 服务商、翻译模型与密钥"
        "视频源（yt-dlp）" -> "更新网页视频解析规则"
        "播放体验" -> "悬浮窗和播放相关权限"
        "存储管理" -> "查看并清理临时文件"
        "关于应用" -> "版本信息与应用说明"
        else -> "查看与管理相关选项"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (expanded) Color(0xFFB9CDF6) else Color(0xFFE8EDF5),
        ),
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        if (onHeaderClick != null) onHeaderClick() else expanded = !expanded
                    }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (expanded) Color(0xFFDDE8FF) else Color(0xFFF0F4FC)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = glyph,
                        contentDescription = null,
                        tint = BrandBright,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = OnBg, style = MaterialTheme.typography.titleMedium)
                    if (title != "转写引擎" && title != "配音设置") {
                        Text(
                            subtitle,
                            color = SubText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
                if (onHeaderClick != null) {
                    Text("›", color = BrandBright, style = MaterialTheme.typography.headlineSmall)
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起设置项" else "展开设置项",
                        tint = if (expanded) BrandBright else SubText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (expanded && onHeaderClick == null) {
                HorizontalDivider(color = Color(0xFFEAF0F8), modifier = Modifier.padding(horizontal = 14.dp))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
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
                // Suspend instead of blocking the IO worker; changing the selected voice or
                // leaving this screen can cancel the preview promptly and release AudioTrack.
                kotlinx.coroutines.delay(playMs + 150)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
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
    val downloadStates by io.translatetube.local.tts.PiperModels.downloadStates.collectAsState()
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
    Spacer(Modifier.height(6.dp))
    io.translatetube.local.tts.PiperModels.catalog.filter { it.supportsChineseOutput }.forEach { def ->
        val isInstalled = installed.contains(def.id)
        val modelState = downloadStates[def.id]
            ?: io.translatetube.local.tts.PiperModelDownloadState(
                status = if (isInstalled) io.translatetube.local.tts.PiperModelDownloadStatus.READY
                    else io.translatetube.local.tts.PiperModelDownloadStatus.NOT_DOWNLOADED,
                progress = if (isInstalled) 100 else 0,
                stageMessage = if (isInstalled) "Piper 模型已就绪" else null,
            )
        val isBusy = modelState.isActive
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
                    isBusy -> {
                        if (modelState.status == io.translatetube.local.tts.PiperModelDownloadStatus.EXTRACTING) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                color = BrandBright,
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { (modelState.progress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                color = BrandBright,
                            )
                        }
                        Text(
                            if (modelState.status == io.translatetube.local.tts.PiperModelDownloadStatus.EXTRACTING) {
                                modelState.stageMessage ?: "下载已完成，正在解压并安装模型…"
                            } else {
                                "${modelState.progress.coerceIn(0, 100)}% · ${modelState.stageMessage ?: "正在准备下载…"}"
                            },
                            color = SubText,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    isInstalled -> Text("已就绪", color = BrandBright, style = MaterialTheme.typography.labelSmall)
                    modelState.status == io.translatetube.local.tts.PiperModelDownloadStatus.FAILED ||
                        modelState.status == io.translatetube.local.tts.PiperModelDownloadStatus.CANCELLED ->
                        Text(
                            modelState.error ?: "下载未完成，可重试",
                            color = Color(0xFFC0392B),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    else -> Text("未下载", color = SubText, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (isBusy) {
                TextButton(onClick = { io.translatetube.local.tts.PiperModels.cancelDownload(def.id) }) {
                    Text("取消")
                }
            } else if (!isInstalled) {
                TextButton(onClick = {
                    val app = ctx.applicationContext as io.translatetube.local.TranslateTubeApp
                    if (app.modelDownload.isPaused) {
                        io.translatetube.local.tts.PiperModels.setPaused(true)
                    }
                    io.translatetube.local.tts.PiperModels.startDownload(
                        ctx.applicationContext,
                        def.id,
                        app.appScope,
                    )
                }) {
                    Text(
                        if (
                            modelState.status == io.translatetube.local.tts.PiperModelDownloadStatus.FAILED ||
                            modelState.status == io.translatetube.local.tts.PiperModelDownloadStatus.CANCELLED
                        ) "重试" else "下载"
                    )
                }
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
                state.checking -> "正在检查上游稳定版并读取本机版本…"
                state.error != null && state.latest == null -> "版本检测失败，查看下方原因后可重试"
                state.current == null && state.latest != null ->
                    "已获取最新版本 ${state.latest}，暂时无法读取本机版本"
                state.current == null -> "尚未读取到本机 yt-dlp 版本，点击“检查更新”重试"
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
                Text(
                    it,
                    color = if (it.startsWith("检查失败") || it.startsWith("更新失败")) DangerRed else BrandBright,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (message == null) {
                state.error?.let {
                    Text(
                        "最近一次检测提示：$it",
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                    message = null
                    scope.launch {
                        try {
                            io.translatetube.local.yt.YtDlpRepo.checkForUpdate(
                                ctx.applicationContext,
                                force = true,
                            )
                            val checked = io.translatetube.local.yt.YtDlpRepo.updateState.value
                            message = when {
                                checked.current != null && checked.latest != null && !checked.hasUpdate ->
                                    "检查结果：已是最新版本"
                                checked.hasUpdate && checked.latest != null ->
                                    "检查结果：发现新版本 ${checked.latest}"
                                checked.latest != null && checked.current == null ->
                                    checked.error?.let { "检查失败：$it" }
                                        ?: "已获取最新版本 ${checked.latest}，暂时无法读取本机版本"
                                else -> checked.error?.let { "检查失败：$it" }
                                    ?: "检查失败：无法获取上游版本信息，请检查网络后重试"
                            }
                        } catch (e: Exception) {
                            message = "检查失败：${e.message ?: "网络错误"}"
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
    val selected = current == id
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(15.dp))
            .clickable { onPick(id) },
        color = if (selected) Color(0xFFEAF1FF) else Color.White,
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.4.dp else 1.dp,
            if (selected) BrandBright else OutlineSoft,
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = { onPick(id) },
                colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
            )
            Text(
                title,
                color = OnBg,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (selected) {
                Surface(color = Color(0xFFD6E5FF), shape = RoundedCornerShape(30.dp)) {
                    Text(
                        "当前使用",
                        color = BrandBright,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}


@Composable
private fun AsrProviderCard(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    onWebsiteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(enabled = enabled, onClick = onClick),
        color = if (selected) Color(0xFFEAF2FF) else Color.White,
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) BrandBright else OutlineSoft,
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = OnBg,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                onWebsiteClick?.let { websiteClick ->
                    IconButton(
                        onClick = websiteClick,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = "打开${title}官网",
                            tint = BrandBright,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                if (selected) Text("✓", color = BrandBright, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    state: io.translatetube.local.data.ModelState,
    ready: Boolean,
    pkgMB: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val active = state.status == io.translatetube.local.data.ModelStatus.DOWNLOADING ||
        state.status == io.translatetube.local.data.ModelStatus.EXTRACTING
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = OnBg,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (ready) {
                Text("已就绪", color = BrandBright, style = MaterialTheme.typography.labelMedium)
            } else if (active) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消$title 下载与安装",
                        tint = DangerRed,
                    )
                }
            } else {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = if (state.status == io.translatetube.local.data.ModelStatus.FAILED) "重试下载$title" else "下载$title",
                        tint = BrandBright,
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
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
                    if (state.totalMB > 0L) {
                        CircularProgressIndicator(
                            progress = { (state.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(36.dp),
                            color = BrandBright,
                            trackColor = Color(0xFFE5EAF0),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = BrandBright,
                            trackColor = Color(0xFFE5EAF0),
                            strokeWidth = 3.dp,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.totalMB > 0L) {
                                "下载中 ${state.progress}% · ${state.downloadedMB}/${state.totalMB} MB"
                            } else {
                                "下载中 · 已下载 ${state.downloadedMB} MB（文件总大小未知）"
                            },
                            color = BrandBright,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            state.stageMessage ?: "下载完成后自动安装",
                            color = SubText,
                            style = MaterialTheme.typography.labelSmall,
                        )
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
                        progress = { (state.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(36.dp),
                        color = BrandBright,
                        trackColor = Color(0xFFE5EAF0),
                        strokeWidth = 3.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "解压安装 ${state.progress.coerceIn(0, 99)}%",
                            color = BrandBright,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            state.stageMessage ?: "正在安装模型…",
                            color = SubText,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            state.status == io.translatetube.local.data.ModelStatus.FAILED -> {
                Text(
                    state.error ?: "下载失败，请检查网络后重试",
                    color = DangerRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Text("下载包约 $pkgMB MB", color = SubText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 在系统应用选择器中打开官网，让用户自行指定浏览器。 */
private fun openOfficialWebsite(ctx: android.content.Context, url: String) {
    runCatching {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        ctx.startActivity(Intent.createChooser(viewIntent, "选择浏览器打开官网"))
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
