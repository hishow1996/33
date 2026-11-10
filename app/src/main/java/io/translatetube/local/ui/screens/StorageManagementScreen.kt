package io.translatetube.local.ui.screens

import android.content.Context
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.translatetube.local.PlayRequest
import io.translatetube.local.TranslateTubeApp
import io.translatetube.local.data.AppCacheStoragePolicy
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.data.SourceType
import io.translatetube.local.data.VideoEntity
import io.translatetube.local.data.VideoHistoryCacheCleaner
import io.translatetube.local.data.ModelFiles
import io.translatetube.local.data.ModelStatus
import io.translatetube.local.player.Phase
import io.translatetube.local.player.PlaybackService
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.tts.PiperModels
import io.translatetube.local.ui.theme.*
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class StorageCategory(val title: String, val hint: String) {
    CACHE("处理缓存", "解析、转写、翻译、配音与恢复缓存"),
    SUBTITLES("字幕与翻译缓存", "字幕、译文及关联处理缓存"),
    MODELS("已下载模型", "本地转写模型、配音模型与配套文件"),
    HISTORY("视频历史记录", "播放历史与收藏记录"),
    DATABASE("应用数据库", "数据库文件、WAL 和 SHM 文件"),
    LOGS("应用日志", "运行记录、异常详情与故障轨迹"),
}
private enum class StorageAction(val title: String) {
    CACHE("清理处理缓存"), SUBTITLES("清理字幕与翻译缓存"), LOGS("清理应用日志"),
}

private data class HistoryDeleteRequest(val keys: List<String>, val clearAll: Boolean = false)
private data class HistoryDeletionOutcome(val removedCount: Int, val failedItems: List<String> = emptyList())
private data class StoragePiperModel(val id: String, val languageLabel: String, val voiceLabel: String, val bytes: Long)
private data class PiperUninstallRequest(val id: String, val voiceLabel: String, val bytes: Long)
private data class StorageSnapshot(
    val total: Long, val availableBytes: Long, val capacityBytes: Long,
    val cache: Long, val subtitleCount: Int, val subtitleBytes: Long,
    val historyCount: Int, val favorites: Int, val models: Long, val tts: Long, val whisper: Long,
    val senseVoice: Long, val piperBytes: Long, val piperCount: Int, val piperModels: List<StoragePiperModel>, val ttsReady: Boolean, val whisperReady: Boolean,
    val senseReady: Boolean, val selectedAsr: String, val database: Long, val logs: Long,
    val logCount: Int, val timestamp: Long, val warnings: List<String>,
)

@Composable
fun StorageManagementScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onPlay: (PlayRequest) -> Unit = {},
    historyOnly: Boolean = false,
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(ctx) }
    val manager = remember { (ctx as TranslateTubeApp).modelDownload }
    val ttsState by manager.tts.collectAsState()
    val asrState by manager.asr.collectAsState()
    val player by PlaybackService.ui.collectAsState()
    val piperDownloads by PiperModels.activeDownloadCount.collectAsState()
    val activePiperModelIds by PiperModels.activeModelIds.collectAsState()
    var data by remember { mutableStateOf<StorageSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val selectedTtsEngineId = remember(refresh) { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("tts_engine", "edge") ?: "edge" }
    val selectedPiperModelId = remember(refresh) { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium" }
    var category by remember(historyOnly) { mutableStateOf(if (historyOnly) StorageCategory.HISTORY else null) }
    var pending by remember { mutableStateOf<StorageAction?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var historyVideos by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var historyLoaded by remember { mutableStateOf(false) }
    var selectedHistoryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var historySelectionMode by remember { mutableStateOf(false) }
    var historyMenuExpanded by remember { mutableStateOf(false) }
    var historyFavoritesOnly by remember(historyOnly) { mutableStateOf(false) }
    var pendingHistoryDelete by remember { mutableStateOf<HistoryDeleteRequest?>(null) }
    var pendingPiperUninstall by remember { mutableStateOf<PiperUninstallRequest?>(null) }

    LaunchedEffect(db) {
        db.videoDao().all().collect { items ->
            historyVideos = items
            historyLoaded = true
            val existingKeys = items.map { it.key }.toSet()
            selectedHistoryKeys = selectedHistoryKeys.intersect(existingKeys)
        }
    }

    BackHandler {
        if (historySelectionMode) {
            historySelectionMode = false
            selectedHistoryKeys = emptySet()
        } else if (historyOnly) onBack()
        else if (category != null) category = null
        else onBack()
    }
    val downloadBusy = PiperModels.hasActiveDownloads || piperDownloads > 0 ||
        ttsState.status in setOf(ModelStatus.DOWNLOADING, ModelStatus.EXTRACTING) ||
        asrState.status in setOf(ModelStatus.DOWNLOADING, ModelStatus.EXTRACTING)
    val playbackBusy = player.isPlaying ||
        player.phase in setOf(Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING, Phase.TRANSLATING, Phase.SYNTHESIZING) ||
        (player.phase == Phase.READY && player.totalLines > 0 && player.preparedCount < player.totalLines) ||
        PlaybackService.instance?.isSpeakerTaskActive == true
    val destructiveBusy = playbackBusy || downloadBusy
    val visibleHistoryVideos = if (historyFavoritesOnly) historyVideos.filter { it.isFavorite } else historyVideos
    val visibleHistoryKeys = visibleHistoryVideos.map { it.key }
    val allVisibleHistorySelected = visibleHistoryKeys.isNotEmpty() &&
        selectedHistoryKeys.containsAll(visibleHistoryKeys)

    // The history page does not display storage statistics. Avoid a full cache/model scan
    // there; while scanning, the previous implementation disabled all history controls.
    val skipStorageScan = historyOnly || category == StorageCategory.HISTORY
    LaunchedEffect(refresh, skipStorageScan) {
        if (skipStorageScan) {
            scanning = false
            return@LaunchedEffect
        }
        scanning = true
        try {
            val snapshot = withContext(Dispatchers.IO) { scanStorage(ctx, db) }
            data = snapshot
            withContext(Dispatchers.IO) {
                val metrics = mapOf(
                    "扫描完成时间戳 ms" to snapshot.timestamp.toString(),
                    "应用数据总占用 bytes" to snapshot.total.toString(),
                    "应用所在分区可用空间 bytes" to snapshot.availableBytes.toString(),
                    "应用所在分区总容量 bytes" to snapshot.capacityBytes.toString(),
                    "临时缓存 bytes" to snapshot.cache.toString(),
                    "字幕缓存条数" to snapshot.subtitleCount.toString(),
                    "字幕缓存占用 bytes" to snapshot.subtitleBytes.toString(),
                    "视频历史条数" to snapshot.historyCount.toString(),
                    "收藏记录条数" to snapshot.favorites.toString(),
                    "模型目录占用 bytes" to snapshot.models.toString(),
                    "Piper 音色模型占用 bytes" to snapshot.piperBytes.toString(),
                    "TTS 模型完整性" to snapshot.ttsReady.toString(),
                    "Whisper 模型完整性" to snapshot.whisperReady.toString(),
                    "SenseVoice 模型完整性" to snapshot.senseReady.toString(),
                    "当前选择的本地转写模型" to snapshot.selectedAsr,
                    "数据库占用 bytes" to snapshot.database.toString(),
                    "日志占用 bytes" to snapshot.logs.toString(),
                    "日志记录条数" to snapshot.logCount.toString(),
                    "扫描警告数量" to snapshot.warnings.size.toString(),
                    "扫描警告摘要" to snapshot.warnings.take(10).joinToString("；").take(1_200).ifBlank { "无" },
                )
                if (snapshot.warnings.isEmpty()) {
                    AppLogStore.info(ctx, "StorageManagementScreen", "本地存储空间扫描完成", diagnosticContext = metrics)
                } else {
                    AppLogStore.warning(
                        ctx,
                        "StorageManagementScreen",
                        "本地存储空间扫描完成，但部分目录或数据库统计不完整",
                        diagnosticContext = metrics,
                    )
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            withContext(Dispatchers.IO) {
                AppLogStore.error(
                    ctx,
                    "StorageManagementScreen",
                    "本地存储空间扫描失败",
                    failure,
                    diagnosticContext = mapOf(
                        "刷新序号" to refresh.toString(),
                        "错误类型" to failure.javaClass.name,
                        "错误摘要" to (failure.message ?: "没有异常消息").take(300),
                    ),
                )
            }
            message = "存储统计失败：${failure.message ?: failure.javaClass.simpleName}"
            messageError = true
        } finally {
            scanning = false
        }
    }

    fun toggleHistorySelection(key: String) {
        selectedHistoryKeys = if (key in selectedHistoryKeys) selectedHistoryKeys - key else selectedHistoryKeys + key
    }
    fun toggleHistorySelectAll(visibleKeys: List<String>) {
        val allKeys = visibleKeys.toSet()
        selectedHistoryKeys = if (allKeys.isNotEmpty() && selectedHistoryKeys.containsAll(allKeys)) {
            selectedHistoryKeys - allKeys
        } else {
            allKeys
        }
    }
    fun toggleHistoryFavorite(key: String) {
        val video = historyVideos.firstOrNull { it.key == key } ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    db.videoDao().setFavorite(key, !video.isFavorite)
                }
            }.onFailure { error ->
                message = "更新收藏状态失败：${error.message ?: error.javaClass.simpleName}"
                messageError = true
            }
        }
    }
    fun requestHistoryDelete(keys: List<String>, clearAll: Boolean = false) {
        if (actionBusy) {
            message = "正在清理历史记录，请稍候。"
            messageError = true
            return
        }
        val uniqueKeys = keys.distinct()
        if (!clearAll && uniqueKeys.isEmpty()) return
        pendingHistoryDelete = HistoryDeleteRequest(uniqueKeys, clearAll)
        message = null
        messageError = false
    }
    fun executeHistoryDelete(request: HistoryDeleteRequest) {
        if (actionBusy) {
            pendingHistoryDelete = null
            message = "正在清理历史记录，请稍候。"
            messageError = true
            return
        }
        pendingHistoryDelete = null
        actionBusy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AppLogStore.info(
                        ctx,
                        "StorageHistory",
                        if (request.clearAll) "开始清除全部视频历史与关联缓存" else "开始清除所选视频历史与关联缓存",
                        diagnosticContext = mapOf(
                            "删除模式" to if (request.clearAll) "全部历史" else "指定历史条目",
                            "请求删除条数" to request.keys.size.toString(),
                            "是否播放或处理" to playbackBusy.toString(),
                            "是否模型下载中" to downloadBusy.toString(),
                        ),
                    )
                    if (request.clearAll) {
                        HistoryDeletionOutcome(VideoHistoryCacheCleaner.deleteAll(ctx, db))
                    } else {
                        var removed = 0
                        val failures = mutableListOf<String>()
                        for (key in request.keys) {
                            val video = db.videoDao().byKey(key) ?: continue
                            try {
                                VideoHistoryCacheCleaner.deleteVideo(ctx, db, video)
                                removed++
                            } catch (failure: Exception) {
                                failures += "${video.title.ifBlank { key }}：${failure.message ?: failure.javaClass.simpleName}"
                                AppLogStore.error(
                                    ctx,
                                    "StorageHistory",
                                    "清除单条视频历史或关联缓存失败",
                                    failure,
                                    diagnosticContext = mapOf(
                                        "视频标题" to video.title.take(120),
                                        "来源类型" to if (video.sourceType == SourceType.YOUTUBE) "YouTube" else if (video.key.startsWith("remote:")) "网页视频" else "本地视频",
                                        "失败文件或缓存键摘要" to "已由缓存清理器保护，不记录原始 URL 或 Cookie",
                                    ),
                                )
                            }
                        }
                        HistoryDeletionOutcome(removed, failures)
                    }
                }
            }
            val outcome = result.getOrNull()
            val hasFailure = result.isFailure || outcome?.failedItems?.isNotEmpty() == true
            messageError = hasFailure
            if (outcome != null) {
                selectedHistoryKeys = emptySet()
                historySelectionMode = false
            }
            val summary = result.fold(
                onSuccess = { completed ->
                    when {
                        completed.failedItems.isNotEmpty() ->
                            "已清除 ${completed.removedCount} 条历史记录及对应缓存；${completed.failedItems.size} 条清理失败，保留原记录以便重试。\n" +
                                completed.failedItems.take(3).joinToString("\n")
                        request.clearAll ->
                            "已清除全部 ${completed.removedCount} 条视频历史及其缓存；原始视频文件和已下载模型已保留。"
                        else ->
                            "已清除 ${completed.removedCount} 条视频历史及对应缓存；原始视频文件和已下载模型未删除。"
                    }
                },
                onFailure = { failure ->
                    "清理失败：${failure.message ?: failure.javaClass.simpleName}"
                },
            )
            message = summary
            withContext(Dispatchers.IO) {
                if (hasFailure) {
                    AppLogStore.warning(ctx, "StorageHistory", "视频历史缓存清理未完全成功", diagnosticContext = mapOf("操作结果" to summary.take(1_000)))
                } else {
                    AppLogStore.info(ctx, "StorageHistory", "视频历史缓存清理成功", diagnosticContext = mapOf("操作结果" to summary.take(500)))
                }
            }
            actionBusy = false
            refresh++
        }
    }

    fun requestPiperUninstall(model: StoragePiperModel) {
        val selected = selectedTtsEngineId == "piper" && selectedPiperModelId == model.id
        when {
            destructiveBusy || actionBusy || scanning -> {
                message = "当前有播放、处理或模型下载任务，暂不能卸载模型。"
                messageError = true
            }
            model.id in activePiperModelIds -> {
                message = "该音色仍被配音引擎占用，释放后才能卸载。"
                messageError = true
            }
            selected -> {
                message = "该音色仍是当前选择项，请先切换配音引擎或选择其他音色。"
                messageError = true
            }
            else -> {
                pendingPiperUninstall = PiperUninstallRequest(model.id, model.voiceLabel, model.bytes)
                message = null
                messageError = false
            }
        }
    }

    fun executePiperUninstall(request: PiperUninstallRequest) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val selectedNow = prefs.getString("tts_engine", "edge") == "piper" &&
            (prefs.getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium") == request.id
        if (destructiveBusy || actionBusy || scanning || request.id in activePiperModelIds || selectedNow) {
            pendingPiperUninstall = null
            message = when {
                request.id in activePiperModelIds -> "配音引擎仍在使用该模型，已取消卸载。"
                selectedNow -> "该音色已成为当前选择项，已取消卸载。"
                else -> "当前有播放、处理或模型下载任务，已取消卸载。"
            }
            messageError = true
            return
        }
        pendingPiperUninstall = null
        actionBusy = true
        message = null
        messageError = false
        scope.launch {
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) { PiperModels.uninstallInstalledModel(ctx, request.id) }
                }
                message = result.fold(
                    onSuccess = { removed ->
                        if (removed) "已卸载 " + request.voiceLabel + "，释放约 " + bytesLabel(request.bytes) + "。"
                        else "模型目录已不存在，存储数据已刷新。"
                    },
                    onFailure = { failure -> "卸载失败：" + (failure.message ?: failure.javaClass.simpleName) },
                )
                messageError = result.isFailure
                withContext(Dispatchers.IO) {
                    runCatching {
                        val failure = result.exceptionOrNull()
                        if (failure != null) {
                            AppLogStore.error(
                                ctx, "StorageManagementScreen", "Piper 音色模型卸载失败", failure,
                                diagnosticContext = mapOf(
                                    "模型 ID" to request.id,
                                    "音色名称" to request.voiceLabel,
                                    "错误摘要" to (failure.message ?: failure.javaClass.simpleName).take(300),
                                ),
                            )
                        } else {
                            AppLogStore.info(
                                ctx, "StorageManagementScreen", "Piper 音色模型卸载完成",
                                diagnosticContext = mapOf(
                                    "模型 ID" to request.id,
                                    "音色名称" to request.voiceLabel,
                                    "卸载前占用 bytes" to request.bytes.toString(),
                                ),
                            )
                        }
                    }
                }
            } finally {
                actionBusy = false
                refresh++
            }
        }
    }
    fun requestClear(action: StorageAction) {
        pending = action
        message = null
        messageError = false
    }
    fun executeClear(action: StorageAction) {
        if (action != StorageAction.LOGS && destructiveBusy) {
            pending = null
            message = "当前有播放、处理或模型下载任务，暂不能清理这类数据。"
            messageError = true
            scope.launch(Dispatchers.IO) {
                AppLogStore.warning(
                    ctx,
                    "StorageManagementScreen",
                    "本地数据清理请求被安全策略阻止",
                    diagnosticContext = mapOf(
                        "清理类别" to action.title,
                        "当前是否正在播放" to player.isPlaying.toString(),
                        "当前视频处理阶段" to player.phase.name,
                        "配音是否仍在准备" to (player.totalLines > 0 && player.preparedCount < player.totalLines).toString(),
                        "配音模型状态" to ttsState.status.name,
                        "转写模型状态" to asrState.status.name,
                        "Piper 下载任务数" to piperDownloads.toString(),
                        "拒绝原因" to "清理期间存在活动中的播放/处理/模型下载任务",
                    ),
                )
            }
            return
        }
        pending = null
        actionBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AppLogStore.info(
                    ctx,
                    "StorageManagementScreen",
                    "开始执行本地数据清理",
                    diagnosticContext = mapOf(
                        "清理类别" to action.title,
                        "操作前临时缓存 bytes" to runCatching { AppCacheStoragePolicy.sizeBytes(ctx.cacheDir, ctx.noBackupFilesDir, ctx.filesDir) }.getOrDefault(-1L).toString(),
                        "操作前日志记录条数" to runCatching { AppLogStore.readRecent(ctx, 800).size }.getOrDefault(-1).toString(),
                        "是否播放/处理中" to playbackBusy.toString(),
                        "是否模型下载中" to downloadBusy.toString(),
                    ),
                )
                runCatching {
                    when (action) {
                        StorageAction.CACHE -> {
                            val before = AppCacheStoragePolicy.sizeBytes(
                                ctx.cacheDir, ctx.noBackupFilesDir, ctx.filesDir,
                            ).coerceAtLeast(0L)
                            VideoHistoryCacheCleaner.clearAllProcessingData(ctx, db)
                            val after = AppCacheStoragePolicy.sizeBytes(
                                ctx.cacheDir, ctx.noBackupFilesDir, ctx.filesDir,
                            ).coerceAtLeast(0L)
                            val freed = (before - after).coerceAtLeast(0L)
                            "已清理全部视频的处理与恢复缓存，实际释放 ${bytesLabel(freed)}；历史记录和已下载模型保留。"
                        }
                        StorageAction.SUBTITLES -> {
                            val count = db.subtitleCacheDao().countEntries()
                            VideoHistoryCacheCleaner.clearAllProcessingData(ctx, db)
                            "已清理 $count 条字幕与翻译缓存，并同步清除关联音频、配音及恢复缓存；历史记录和已下载模型保留。"
                        }
                        StorageAction.LOGS ->
                            if (AppLogStore.clear(ctx)) "应用日志已清理，之后的新日志仍会继续保存。"
                            else "部分日志文件未能删除，请稍后重试。"
                    }
                }
            }
            withContext(Dispatchers.IO) {
                val failure = result.exceptionOrNull()
                val actionLabel = action.title
                if (failure != null) {
                    AppLogStore.error(
                        ctx,
                        "StorageManagementScreen",
                        "本地数据清理操作失败：$actionLabel",
                        failure,
                        diagnosticContext = mapOf(
                            "清理类别" to actionLabel,
                            "异常类型" to failure.javaClass.name,
                            "异常摘要" to (failure.message ?: "没有异常消息").take(300),
                        ),
                    )
                } else {
                    val summary = result.getOrNull().orEmpty()
                    val partial = summary.contains("部分") || summary.contains("失败") || summary.contains("未能")
                    val diagnostic = mapOf(
                        "清理类别" to actionLabel,
                        "操作结果摘要" to summary.take(500),
                        "当前临时缓存 bytes" to runCatching { AppCacheStoragePolicy.sizeBytes(ctx.cacheDir, ctx.noBackupFilesDir, ctx.filesDir) }.getOrDefault(-1L).toString(),
                        "当前日志记录条数" to runCatching { AppLogStore.readRecent(ctx, 800).size }.getOrDefault(-1).toString(),
                        "结果分类" to if (partial) "部分完成或仍有资源占用" else "成功完成",
                    )
                    if (partial) {
                        AppLogStore.warning(ctx, "StorageManagementScreen", "本地数据清理部分完成：$actionLabel", diagnosticContext = diagnostic)
                    } else {
                        AppLogStore.info(ctx, "StorageManagementScreen", "本地数据清理成功：$actionLabel", diagnosticContext = diagnostic)
                    }
                }
            }
            message = result.getOrElse { failure ->
                messageError = true
                "操作失败：${failure.message ?: failure.javaClass.simpleName}"
            }
            messageError = messageError || message.orEmpty().contains("部分") ||
                message.orEmpty().contains("失败") || message.orEmpty().contains("未能")
            actionBusy = false
            refresh++
        }
    }

    Column(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp)).background(Color(0xFFF3F6FC))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val isHistoryPage = category == StorageCategory.HISTORY
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isHistoryPage && historySelectionMode) {
                IconButton(
                    onClick = {
                        historySelectionMode = false
                        selectedHistoryKeys = emptySet()
                    },
                ) {
                    Icon(Icons.Filled.Close, "退出选择", tint = OnBg)
                }
                Text(
                    "已选 ${selectedHistoryKeys.count { it in visibleHistoryKeys }} 项",
                    modifier = Modifier.weight(1f),
                    color = OnBg,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    enabled = !actionBusy && visibleHistoryKeys.isNotEmpty(),
                    onClick = { toggleHistorySelectAll(visibleHistoryKeys) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text(if (allVisibleHistorySelected) "取消全选" else "全选") }
            } else {
                IconButton(onClick = { if (!historyOnly && category != null) category = null else onBack() }) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = OnBg)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(category?.title ?: "存储管理", color = OnBg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (category != StorageCategory.HISTORY) {
                        Text(
                            if (category == null) "本地数据总览 · 不会自动上传"
                            else "分类占用与可执行操作",
                            color = SubText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = { refresh++ }, enabled = !scanning && !actionBusy) {
                    Icon(Icons.Filled.Refresh, "重新统计", tint = BrandBright)
                }
                if (isHistoryPage) {
                    Box {
                        IconButton(onClick = { historyMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, "更多操作", tint = BrandBright)
                        }
                        DropdownMenu(
                            expanded = historyMenuExpanded,
                            onDismissRequest = { historyMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("选择记录") },
                                leadingIcon = { Icon(Icons.Filled.CheckBox, null, tint = BrandBright) },
                                enabled = !actionBusy && historyVideos.isNotEmpty(),
                                onClick = {
                                    historyMenuExpanded = false
                                    historySelectionMode = true
                                    selectedHistoryKeys = emptySet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("全部清除", color = DangerRed) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = DangerRed) },
                                enabled = !actionBusy && historyVideos.isNotEmpty(),
                                onClick = {
                                    historyMenuExpanded = false
                                    requestHistoryDelete(historyVideos.map { it.key }, clearAll = true)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BrandBright, trackColor = OutlineSoft)
        message?.let {
            Surface(Modifier.fillMaxWidth(), color = if (messageError) Color(0xFFFFF1F0) else Color(0xFFE8F5E9), shape = RoundedCornerShape(10.dp)) {
                Text(it, Modifier.padding(11.dp), color = if (messageError) DangerRed else Color(0xFF216E39), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (category == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                StorageHome(data, scanning) { category = it; message = null }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (category == StorageCategory.HISTORY) {
                    StorageHistoryDetail(
                        videos = historyVideos,
                        actionBusy = actionBusy,
                        selectionMode = historySelectionMode,
                        selectedKeys = selectedHistoryKeys,
                        onToggleSelection = ::toggleHistorySelection,
                        favoritesOnly = historyFavoritesOnly,
                        onToggleFavoritesOnly = {
                            historyFavoritesOnly = !historyFavoritesOnly
                            selectedHistoryKeys = emptySet()
                        },
                        onToggleFavorite = ::toggleHistoryFavorite,
                        onDeleteSingle = { requestHistoryDelete(listOf(it)) },
                        onDeleteSelected = { requestHistoryDelete(selectedHistoryKeys.toList()) },
                        onPlay = onPlay,
                    )
                } else {
                    StorageDetail(
                        category = category!!,
                        data = data,
                        scanning = scanning,
                        busy = destructiveBusy,
                        actionBusy = actionBusy,
                        onAction = ::requestClear,
                        onOpenLogs = onOpenLogs,
                        selectedPiperModelId = selectedPiperModelId.takeIf { selectedTtsEngineId == "piper" },
                        activePiperModelIds = activePiperModelIds,
                        canUninstallPiperModels = !destructiveBusy && !actionBusy && !scanning,
                        onUninstallPiper = ::requestPiperUninstall,
                    )
                }
            }
        }
    }

    pendingHistoryDelete?.let { request ->
        val description = when {
            request.clearAll -> "将删除全部视频历史（包括收藏），并清理应用管理的字幕/翻译、配音 PCM、转写音轨和 HLS 分段缓存。如果有视频正在解析、处理或播放，会先结束对应会话；模型下载不会停止。手机原始视频和已下载模型不会删除。"
            request.keys.size == 1 -> "将删除这条视频历史及其字幕/翻译、配音 PCM、转写音轨和 HLS 分段缓存。如果该视频正在解析、处理或播放，会先结束对应会话。手机原始视频与已下载模型会保留。"
            else -> "将删除所选的 ${request.keys.size} 条视频历史及各自关联缓存；其中正在处理的视频会先结束对应会话。手机原始视频与已下载模型会保留。"
        }
        AlertDialog(
            onDismissRequest = { if (!actionBusy) pendingHistoryDelete = null },
            title = { Text(if (request.clearAll) "清除全部视频历史？" else if (request.keys.size == 1) "删除这条视频历史？" else "删除所选历史？", color = OnBg) },
            text = { Text(description, color = SubText) },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = { executeHistoryDelete(request) },
                ) { Text(if (actionBusy) "处理中…" else "确认删除", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { if (!actionBusy) pendingHistoryDelete = null }) { Text("取消", color = SubText) } },
            containerColor = SurfaceDark,
        )
    }

    pendingPiperUninstall?.let { request ->
        AlertDialog(
            onDismissRequest = { if (!actionBusy) pendingPiperUninstall = null },
            title = { Text("卸载 " + request.voiceLabel + "？", color = OnBg) },
            text = {
                Text(
                    "将删除这个 Piper 音色模型，预计释放 " + bytesLabel(request.bytes) + "。其他模型、历史记录和字幕缓存不会受影响。",
                    color = SubText,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy && !destructiveBusy && request.id !in activePiperModelIds &&
                        !(selectedTtsEngineId == "piper" && selectedPiperModelId == request.id),
                    onClick = { executePiperUninstall(request) },
                ) { Text(if (actionBusy) "处理中…" else "确认卸载", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { if (!actionBusy) pendingPiperUninstall = null }) { Text("取消", color = SubText) }
            },
            containerColor = SurfaceDark,
        )
    }
    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!actionBusy) pending = null },
            title = { Text(action.title + "？", color = OnBg) },
            text = {
                Text(when (action) {
                    StorageAction.CACHE -> "删除所有视频的解析、转写、翻译、配音和任务恢复缓存；历史记录、原始视频、已下载模型和应用设置保留。重新打开视频将从头解析并重新获取音频。"
                    StorageAction.SUBTITLES -> "删除字幕原文、译文及关联的音频、配音和恢复缓存；历史记录、原始视频和已下载模型保留。以后重新打开视频将从头处理。"
                    StorageAction.LOGS -> "将删除保存的日志文件和上次异常详情。建议先到日志页面导出需要保留的记录。"
                }, color = SubText)
            },
            confirmButton = {
                TextButton(enabled = !actionBusy && (action == StorageAction.LOGS || !destructiveBusy), onClick = { executeClear(action) }) {
                    Text(if (actionBusy) "处理中…" else "确认清理", color = DangerRed)
                }
            },
            dismissButton = { TextButton(onClick = { if (!actionBusy) pending = null }) { Text("取消", color = SubText) } },
            containerColor = SurfaceDark,
        )
    }
}

@Composable
private fun StorageHome(data: StorageSnapshot?, scanning: Boolean, onOpen: (StorageCategory) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Surface(
                Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, OutlineSoft),
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("应用占用", color = SubText, style = MaterialTheme.typography.bodySmall)
                            Text(
                                data?.let { bytesLabel(it.total) } ?: if (scanning) "统计中…" else "暂不可用",
                                color = OnBg,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = BrandBright,
                                strokeWidth = 2.5.dp,
                            )
                        }
                    }
                    StorageBreakdown(data)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StorageMetric(
                            "可清理缓存",
                            data?.let { bytesLabel(it.cache) } ?: "—",
                            Icons.Filled.FolderOpen,
                            Modifier.weight(1f),
                        )
                        StorageMetric(
                            "模型文件",
                            data?.let { bytesLabel(it.models) } ?: "—",
                            Icons.Filled.Memory,
                            Modifier.weight(1f),
                        )
                    }
                    MetricLine(
                        "所在分区可用 / 总容量",
                        data?.let {
                            if (it.availableBytes >= 0L && it.capacityBytes >= 0L) {
                                "${bytesLabel(it.availableBytes)} / ${bytesLabel(it.capacityBytes)}"
                            } else "暂不可用"
                        } ?: "—",
                    )
                }
            }
        }
        item {
            Text(
                "数据分类",
                color = OnBg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(StorageCategory.values().toList()) { item ->
            val value = when (item) {
                StorageCategory.CACHE -> data?.let { "可清理 ${bytesLabel(it.cache)}" } ?: "统计中…"
                StorageCategory.SUBTITLES -> data?.let { "${it.subtitleCount} 条 · ${bytesLabel(it.subtitleBytes)}" } ?: "统计中…"
                StorageCategory.MODELS -> data?.let { bytesLabel(it.models) } ?: "统计中…"
                StorageCategory.HISTORY -> data?.let { "${it.historyCount} 条 · 收藏 ${it.favorites}" } ?: "统计中…"
                StorageCategory.DATABASE -> data?.let { bytesLabel(it.database) } ?: "统计中…"
                StorageCategory.LOGS -> data?.let { "${it.logCount} 条 · ${bytesLabel(it.logs)}" } ?: "统计中…"
            }
            StorageRow(item, value) { onOpen(item) }
        }
        data?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
            item {
                NoticeBox("部分数据统计不完整：\n" + warnings.joinToString("\n"), true)
            }
        }
        data?.let { info ->
            item {
                Text(
                    "上次统计：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(info.timestamp))}",
                    color = SubText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private data class StorageBreakdownSlice(
    val title: String,
    val bytes: Long,
    val color: Color,
)

@Composable
private fun StorageBreakdown(data: StorageSnapshot?) {
    val snapshot = data ?: return
    if (
        snapshot.total <= 0L ||
        snapshot.cache < 0L ||
        snapshot.models < 0L ||
        snapshot.database < 0L ||
        snapshot.logs < 0L
    ) return

    val knownBytes = listOf(snapshot.cache, snapshot.models, snapshot.database, snapshot.logs)
        .fold(0L, ::addSaturated)
    val otherBytes = (snapshot.total - knownBytes).coerceAtLeast(0L)
    val slices = listOf(
        StorageBreakdownSlice("缓存", snapshot.cache, BrandBright),
        StorageBreakdownSlice("模型", snapshot.models, MutedGreen),
        StorageBreakdownSlice("数据库", snapshot.database, Color(0xFFE5A642)),
        StorageBreakdownSlice("日志", snapshot.logs, Color(0xFF8E78C8)),
        StorageBreakdownSlice("其他", otherBytes, Color(0xFFB7C2D2)),
    )
    val visibleSlices = slices.filter { it.bytes > 0L }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .height(9.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(OutlineSoft),
        ) {
            visibleSlices.forEach { slice ->
                Box(
                    Modifier.weight(slice.bytes.toFloat())
                        .fillMaxHeight()
                        .background(slice.color),
                )
            }
        }
        slices.chunked(3).forEach { group ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.forEach { slice ->
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier.size(7.dp)
                                .background(slice.color, CircleShape),
                        )
                        Text(
                            "${slice.title} ${bytesLabel(slice.bytes)}",
                            color = SubText,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StorageDetail(
    category: StorageCategory,
    data: StorageSnapshot?,
    scanning: Boolean,
    busy: Boolean,
    actionBusy: Boolean,
    onAction: (StorageAction) -> Unit,
    onOpenLogs: () -> Unit,
    selectedPiperModelId: String?,
    activePiperModelIds: Set<String>,
    canUninstallPiperModels: Boolean,
    onUninstallPiper: (StoragePiperModel) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
        item {
            Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, OutlineSoft)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("当前占用", color = SubText, style = MaterialTheme.typography.bodySmall)
                    Text(primaryValue(category, data, scanning), color = OnBg, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    HorizontalDivider(color = OutlineSoft)
                    when (category) {
                        StorageCategory.CACHE -> {
                            if (busy) NoticeBox("播放、处理或模型下载期间不能清理。", true)
                            ActionButton(if (actionBusy) "清理中…" else "清理处理缓存", !busy && !actionBusy && !scanning) { onAction(StorageAction.CACHE) }
                        }
                        StorageCategory.SUBTITLES -> {
                            MetricLine("缓存条目", data?.subtitleCount?.toString() ?: "—")
                            MetricLine("字幕 JSON 内容估算", data?.let { bytesLabel(it.subtitleBytes) } ?: "—")
                            if (busy) NoticeBox("播放、处理字幕或下载模型期间不能清理。", true)
                            ActionButton(if (actionBusy) "清理中…" else "清理字幕与翻译缓存", !busy && !actionBusy && !scanning) { onAction(StorageAction.SUBTITLES) }
                        }
                        StorageCategory.MODELS -> {
                            MetricLine("模型目录总占用", data?.let { bytesLabel(it.models) } ?: "—")
                            MetricLine("Kokoro 与配音辅助文件", data?.let { bytesLabel(it.tts) } ?: "—")
                            MetricLine("Piper 音色模型目录", data?.let { bytesLabel(it.piperBytes) } ?: "—")
                            MetricLine("Whisper 与共享 VAD", data?.let { bytesLabel(it.whisper) } ?: "—")
                            MetricLine("SenseVoice", data?.let { bytesLabel(it.senseVoice) } ?: "—")
                            HorizontalDivider(color = OutlineSoft)
                            ModelStatusLine("Kokoro 配音模型", data?.ttsReady == true)
                            ModelStatusLine("Whisper 转写模型", data?.whisperReady == true)
                            ModelStatusLine("SenseVoice 转写模型", data?.senseReady == true)
                            MetricLine("已安装 Piper 音色模型", data?.piperCount?.toString() ?: "—")
                            MetricLine("当前选择的本地转写模型", data?.selectedAsr ?: "—")
                            HorizontalDivider(color = OutlineSoft)
                            Text("Piper 音色清单", color = OnBg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (data?.piperModels.isNullOrEmpty()) {
                                Text("暂无已安装音色", color = SubText, style = MaterialTheme.typography.bodySmall)
                            } else {
                                data?.piperModels.orEmpty().forEach { model ->
                                    StoragePiperModelRow(
                                        model = model,
                                        selected = selectedPiperModelId == model.id,
                                        inUse = model.id in activePiperModelIds,
                                        canUninstall = canUninstallPiperModels,
                                        onUninstall = { onUninstallPiper(model) },
                                    )
                                }
                            }
                        }
                        StorageCategory.HISTORY -> {
                            MetricLine("历史记录总数", data?.historyCount?.toString() ?: "—")
                            MetricLine("收藏记录", data?.favorites?.toString() ?: "—")
                        }
                        StorageCategory.DATABASE -> {
                            MetricLine("数据库及附属文件", data?.let { bytesLabel(it.database) } ?: "—")
                        }
                        StorageCategory.LOGS -> {
                            MetricLine("可读取日志条数（最多 800）", data?.logCount?.toString() ?: "—")
                            MetricLine("日志与异常文件占用", data?.let { bytesLabel(it.logs) } ?: "—")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f), enabled = !actionBusy, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright)) { Text("查看 / 导出日志") }
                                OutlinedButton(onClick = { onAction(StorageAction.LOGS) }, modifier = Modifier.weight(1f), enabled = !actionBusy && !scanning, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed)) { Text("清理日志") }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun StoragePiperModelRow(
    model: StoragePiperModel,
    selected: Boolean,
    inUse: Boolean,
    canUninstall: Boolean,
    onUninstall: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF7F9FD),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, OutlineSoft),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 11.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.voiceLabel, color = OnBg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(model.languageLabel + " · " + bytesLabel(model.bytes), color = SubText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                if (selected || inUse) Text(if (selected) "当前选择" else "使用中", color = if (selected) BrandBright else MutedGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            }
            TextButton(
                onClick = onUninstall,
                enabled = canUninstall && !selected && !inUse,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(when { selected -> "当前选择"; inUse -> "使用中"; else -> "卸载" }, color = if (selected || inUse) SubText else DangerRed)
            }
        }
    }
}
@Composable
private fun StorageHistoryDetail(
    videos: List<VideoEntity>,
    actionBusy: Boolean,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    onToggleSelection: (String) -> Unit,
    favoritesOnly: Boolean,
    onToggleFavoritesOnly: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDeleteSingle: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
) {
    val shownVideos = if (favoritesOnly) videos.filter { it.isFavorite } else videos
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding = PaddingValues(bottom = if (selectionMode) 88.dp else 14.dp),
        ) {
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, OutlineSoft),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = !favoritesOnly,
                            onClick = { if (favoritesOnly) onToggleFavoritesOnly() },
                            label = { Text("全部") },
                        )
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { if (!favoritesOnly) onToggleFavoritesOnly() },
                            label = { Text("收藏") },
                            leadingIcon = {
                                Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
            if (shownVideos.isEmpty()) {
                item {
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, OutlineSoft),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = SubText, modifier = Modifier.size(28.dp))
                            Text(
                                if (favoritesOnly) "还没有收藏视频" else "还没有视频历史记录",
                                color = OnBg,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            } else {
                items(shownVideos, key = { it.key }) { video ->
                    StorageHistoryVideoRow(
                        video = video,
                        selected = video.key in selectedKeys,
                        selectionMode = selectionMode,
                        enabled = !actionBusy,
                        favoriteEnabled = !actionBusy,
                        onClick = {
                            if (selectionMode) onToggleSelection(video.key)
                            else onPlay(video.toHistoryPlayRequest())
                        },
                        onToggleSelection = { onToggleSelection(video.key) },
                        onToggleFavorite = { onToggleFavorite(video.key) },
                        onDelete = { onDeleteSingle(video.key) },
                    )
                }
            }
        }
        if (selectionMode) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 2.dp),
                color = Color.White,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, OutlineSoft),
                shadowElevation = 4.dp,
            ) {
                Button(
                    onClick = onDeleteSelected,
                    enabled = !actionBusy && selectedKeys.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(8.dp).height(48.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerRed,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE5E8EE),
                        disabledContentColor = SubText,
                    ),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("删除所选（${selectedKeys.size}）")
                }
            }
        }
    }
}

@Composable
private fun StorageHistoryVideoRow(
    video: VideoEntity,
    selected: Boolean,
    selectionMode: Boolean,
    enabled: Boolean,
    favoriteEnabled: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected && selectionMode) Color(0xFFEAF2FF) else Color.White)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { if (enabled) onToggleSelection() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = BrandBright),
            )
            Spacer(Modifier.width(3.dp))
        }
        AsyncImage(
            model = video.thumbUrl?.takeIf { it.isNotBlank() } ?: video.localUri,
            contentDescription = null,
            modifier = Modifier.size(78.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFDCE8F9)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                video.title.ifEmpty { video.key },
                color = OnBg,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (video.channel.ifEmpty {
                    when {
                        video.sourceType == SourceType.YOUTUBE -> "YouTube"
                        video.key.startsWith("remote:") -> "网页视频"
                        else -> "本地视频"
                    }
                }) + " · 中文配音" +
                    if (video.durationSec > 0) " · " + SubtitleParsers.fmtTime(video.durationSec * 1000) else "",
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!selectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("继续观看", color = BrandBright, style = MaterialTheme.typography.labelLarge)
                    if (video.isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Star, contentDescription = "已收藏", tint = Color(0xFFE3A008), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (!selectionMode) {
            IconButton(
                enabled = favoriteEnabled,
                onClick = onToggleFavorite,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (video.isFavorite) "取消收藏" else "添加收藏",
                    tint = if (video.isFavorite) Color(0xFFE3A008) else SubText,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(enabled = enabled, onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "删除这条历史和对应缓存", tint = DangerRed)
            }
        }
    }
}

private fun VideoEntity.toHistoryPlayRequest(): PlayRequest {
    val request = when {
        sourceType == SourceType.YOUTUBE ->
            PlayRequest(videoId = videoId ?: key.removePrefix("youtube:"))
        key.startsWith("remote:") ->
            PlayRequest(mediaUrl = localUri ?: key.removePrefix("remote:"))
        else ->
            PlayRequest(localUri = localUri ?: key.removePrefix("local:"))
    }
    return request.copy(preferReadyHistoryResume = true)
}

@Composable
private fun StorageRow(category: StorageCategory, value: String, onClick: () -> Unit) {
    val image = when (category) {
        StorageCategory.CACHE -> Icons.Filled.FolderOpen
        StorageCategory.SUBTITLES -> Icons.Filled.Subtitles
        StorageCategory.MODELS -> Icons.Filled.Memory
        StorageCategory.HISTORY -> Icons.Filled.History
        StorageCategory.DATABASE -> Icons.Filled.FolderOpen
        StorageCategory.LOGS -> Icons.Filled.Description
    }
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, OutlineSoft),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFFF0F4FC)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(image, null, tint = BrandBright, modifier = Modifier.size(21.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(category.title, color = OnBg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(value, color = BrandBright, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "进入${category.title}", tint = SubText)
        }
    }
}

@Composable
private fun StorageMetric(title: String, value: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(13.dp), color = Color(0xFFF5F7FC), border = BorderStroke(1.dp, OutlineSoft)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = BrandBright, modifier = Modifier.size(19.dp))
            Text(title, color = SubText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = OnBg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MetricLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = SubText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = OnBg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelStatusLine(title: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (ready) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber, null, tint = if (ready) MutedGreen else WarnYellow, modifier = Modifier.size(19.dp))
        Text(title, color = OnBg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(if (ready) "已就绪" else "未就绪 / 待检查", color = if (ready) MutedGreen else WarnYellow, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NoticeBox(text: String, warning: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = if (warning) Color(0xFFFFF7E6) else Color(0xFFF0F4FC)) {
        Text(text, Modifier.padding(10.dp), color = if (warning) Color(0xFF8A4B08) else SubText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand, disabledContainerColor = OutlineSoft, contentColor = Color.White, disabledContentColor = SubText)
    ) { Text(title) }
}

private fun primaryValue(category: StorageCategory, data: StorageSnapshot?, scanning: Boolean): String {
    if (data == null) return if (scanning) "统计中…" else "暂不可用"
    return when (category) {
        StorageCategory.CACHE -> bytesLabel(data.cache)
        StorageCategory.SUBTITLES -> bytesLabel(data.subtitleBytes)
        StorageCategory.MODELS -> bytesLabel(data.models)
        StorageCategory.HISTORY -> "${data.historyCount} 条记录"
        StorageCategory.DATABASE -> bytesLabel(data.database)
        StorageCategory.LOGS -> bytesLabel(data.logs)
    }
}

private suspend fun scanStorage(context: Context, db: AppDatabase): StorageSnapshot {
    val warnings = mutableListOf<String>()
    fun measured(label: String, file: File): Long = runCatching { directorySize(file) }.getOrElse {
        warnings += "${label} 统计失败：${it.javaClass.simpleName}"; -1L
    }

    val filesDir = context.filesDir
    val filesTree = scanDirectoryTreeOnce(
        root = filesDir,
        targets = mapOf(
            "models" to File(filesDir, "models"),
            "tts" to ModelFiles.ttsDir(context),
            "piper" to PiperModels.piperRoot(context),
            "whisper" to ModelFiles.asrDir(context),
            "senseVoice" to ModelFiles.senseVoiceDir(context),
            "logs" to File(filesDir, "app_logs"),
            "crash" to File(filesDir, "crash"),
        ) + PiperModels.catalog.associate { definition ->
            ("piperModel:" + definition.id) to PiperModels.modelDir(context, definition.id)
        },
        warnings = warnings,
    )
    val models = filesTree.totalFor("models")
    val ttsDirectoryBytes = filesTree.totalFor("tts")
    val piperBytes = filesTree.totalFor("piper")
    val tts = if (ttsDirectoryBytes < 0L || piperBytes < 0L) {
        -1L
    } else {
        (ttsDirectoryBytes - piperBytes).coerceAtLeast(0L)
    }
    val whisper = filesTree.totalFor("whisper")
    val sense = filesTree.totalFor("senseVoice")
    val logA = filesTree.totalFor("logs")
    val logB = filesTree.totalFor("crash")
    val logBytes = if (logA < 0L || logB < 0L) -1L else addSaturated(logA, logB)

    val cache = runCatching {
        AppCacheStoragePolicy.sizeBytes(context.cacheDir, context.noBackupFilesDir, context.filesDir)
    }.getOrElse {
        warnings += "临时缓存统计失败：${it.javaClass.simpleName}"; -1L
    }
    val subtitleCount = runCatching { db.subtitleCacheDao().countEntries() }.getOrElse {
        warnings += "字幕缓存条数统计失败：${it.javaClass.simpleName}"; -1
    }
    val subtitleBytes = runCatching { db.subtitleCacheDao().payloadBytes() }.getOrElse {
        warnings += "字幕缓存内容统计失败：${it.javaClass.simpleName}"; -1L
    }
    val history = runCatching { db.videoDao().countAll() }.getOrElse {
        warnings += "视频历史统计失败：${it.javaClass.simpleName}"; -1
    }
    val favorites = runCatching { db.videoDao().countFavorites() }.getOrElse {
        warnings += "收藏记录统计失败：${it.javaClass.simpleName}"; -1
    }
    val installedPiperIds = runCatching { PiperModels.installedIds(context) }.getOrElse {
        warnings += "Piper 模型状态读取失败：${it.javaClass.simpleName}"
        null
    }
    val piperCount = installedPiperIds?.size ?: -1
    val piperModels = installedPiperIds?.let { installedIds ->
        PiperModels.catalog.filter { it.id in installedIds }.map { definition ->
            StoragePiperModel(
                id = definition.id,
                languageLabel = definition.langLabel,
                voiceLabel = definition.voiceLabel,
                bytes = filesTree.totalFor("piperModel:" + definition.id),
            )
        }
    }.orEmpty()
    val ttsReady = runCatching { ModelFiles.ttsReady(context) }.getOrDefault(false)
    val whisperReady = runCatching { ModelFiles.whisperReady(context) }.getOrDefault(false)
    val senseReady = runCatching { ModelFiles.senseVoiceReady(context) }.getOrDefault(false)
    val selectedAsr = ModelFiles.localAsrModelDisplayName(ModelFiles.selectedLocalAsrModelId(context))
    val dbFile = context.getDatabasePath("translatetube.db")
    val databaseBytes = runCatching {
        val parent = dbFile.parentFile
        if (parent == null || !parent.exists()) {
            0L
        } else {
            val entries = parent.listFiles { file ->
                file.isFile && (file.name == dbFile.name || file.name.startsWith(dbFile.name + "-"))
            } ?: throw IllegalStateException("数据库目录无法读取")
            entries.fold(0L) { total, file -> addSaturated(total, file.length()) }
        }
    }.getOrElse {
        warnings += "数据库文件统计失败：${it.javaClass.simpleName}"; -1L
    }
    val logCount = runCatching { AppLogStore.readRecent(context, 800).size }.getOrElse {
        warnings += "日志条数统计失败：${it.javaClass.simpleName}"; -1
    }
    val storageStats = runCatching { StatFs(filesDir.absolutePath) }.getOrElse {
        warnings += "应用所在分区空间统计失败：${it.javaClass.simpleName}"
        null
    }
    val availableBytes = storageStats?.let {
        runCatching { it.availableBytes.coerceAtLeast(0L) }.getOrDefault(-1L)
    } ?: -1L
    val capacityBytes = storageStats?.let {
        runCatching { it.totalBytes.coerceAtLeast(0L) }.getOrDefault(-1L)
    } ?: -1L
    if (storageStats != null && (availableBytes < 0L || capacityBytes < 0L)) {
        warnings += "应用所在分区可用空间读取失败"
    }

    val parts = listOf(
        filesTree.totalBytes,
        measured("应用缓存", context.cacheDir),
        databaseBytes,
        measured("无备份文件", context.noBackupFilesDir),
        measured("代码缓存", context.codeCacheDir),
    )
    val total = if (parts.any { it < 0L }) -1L else parts.fold(0L, ::addSaturated)
    return StorageSnapshot(
        total = total,
        availableBytes = availableBytes,
        capacityBytes = capacityBytes,
        cache = cache,
        subtitleCount = subtitleCount,
        subtitleBytes = subtitleBytes,
        historyCount = history,
        favorites = favorites,
        models = models,
        tts = tts,
        whisper = whisper,
        senseVoice = sense,
        piperBytes = piperBytes,
        piperCount = piperCount,
        piperModels = piperModels,
        ttsReady = ttsReady,
        whisperReady = whisperReady,
        senseReady = senseReady,
        selectedAsr = selectedAsr,
        database = databaseBytes,
        logs = logBytes,
        logCount = logCount,
        timestamp = System.currentTimeMillis(),
        warnings = warnings,
    )
}

private data class DirectoryTreeSnapshot(
    val totalBytes: Long,
    val bytesByTarget: Map<String, Long>,
) {
    fun totalFor(key: String): Long = bytesByTarget[key] ?: -1L
}

/** Walks filesDir once and collects nested model/log directory sizes at the same time. */
private fun scanDirectoryTreeOnce(
    root: File,
    targets: Map<String, File>,
    warnings: MutableList<String>,
): DirectoryTreeSnapshot {
    val targetPaths = targets.mapValues { (_, directory) ->
        directory.absolutePath.trimEnd(File.separatorChar)
    }
    val sizes = targetPaths.keys.associateWith { 0L }.toMutableMap()
    var totalBytes = 0L
    if (!root.exists()) {
        return DirectoryTreeSnapshot(0L, sizes)
    }
    root.walkTopDown()
        .onFail { failedFile, error ->
            warnings += "目录 ${failedFile.name} 读取失败：${error.javaClass.simpleName}"
        }
        .forEach { file ->
            if (!file.isFile) return@forEach
            val bytes = file.length().coerceAtLeast(0L)
            totalBytes = addSaturated(totalBytes, bytes)
            val filePath = file.absolutePath
            for ((key, targetPath) in targetPaths) {
                if (filePath == targetPath || filePath.startsWith(targetPath + File.separator)) {
                    sizes[key] = addSaturated(sizes.getValue(key), bytes)
                }
            }
        }
    return DirectoryTreeSnapshot(totalBytes, sizes)
}
private fun directorySize(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length().coerceAtLeast(0L)
    var total = 0L
    file.walkTopDown().filter { it.isFile }.forEach { total = addSaturated(total, it.length().coerceAtLeast(0L)) }
    return total
}

private fun addSaturated(total: Long, bytes: Long): Long {
    require(total >= 0L) { "存储累计值不能为负数" }
    if (bytes <= 0L) return total
    return if (total > Long.MAX_VALUE - bytes) Long.MAX_VALUE else total + bytes
}

private fun bytesLabel(bytes: Long): String = when {
    bytes < 0L -> "统计失败"
    bytes >= 1L shl 30 -> "%.2f GB".format(java.util.Locale.getDefault(), bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.getDefault(), bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.0f KB".format(java.util.Locale.getDefault(), bytes / 1024.0)
    else -> "$bytes B"
}
