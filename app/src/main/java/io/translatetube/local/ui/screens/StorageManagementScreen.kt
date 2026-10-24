package io.translatetube.local.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
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
    CACHE("临时缓存", "配音音频、转写音频和 HLS 分段"),
    SUBTITLES("字幕与翻译缓存", "字幕原文、译文和缓存条目"),
    MODELS("已下载模型", "本地转写模型、配音模型与配套文件"),
    HISTORY("视频历史记录", "播放历史与收藏记录"),
    DATABASE("应用数据库", "数据库文件、WAL 和 SHM 文件"),
    LOGS("应用日志", "运行记录、异常详情与故障轨迹"),
}
private enum class StorageAction(val title: String) {
    CACHE("清理临时缓存"), SUBTITLES("清理字幕与翻译缓存"), LOGS("清理应用日志"),
}

private data class HistoryDeleteRequest(val keys: List<String>, val clearAll: Boolean = false)
private data class HistoryDeletionOutcome(val removedCount: Int, val failedItems: List<String> = emptyList())
private data class StorageSnapshot(
    val total: Long, val cache: Long, val subtitleCount: Int, val subtitleBytes: Long,
    val historyCount: Int, val favorites: Int, val models: Long, val tts: Long, val whisper: Long,
    val senseVoice: Long, val piperCount: Int, val ttsReady: Boolean, val whisperReady: Boolean,
    val senseReady: Boolean, val selectedAsr: String, val database: Long, val logs: Long,
    val logCount: Int, val timestamp: Long, val warnings: List<String>,
)

@Composable
fun StorageManagementScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onPlay: (PlayRequest) -> Unit = {},
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(ctx) }
    val manager = remember { (ctx as TranslateTubeApp).modelDownload }
    val ttsState by manager.tts.collectAsState()
    val asrState by manager.asr.collectAsState()
    val player by PlaybackService.ui.collectAsState()
    val piperDownloads by PiperModels.activeDownloadCount.collectAsState()
    var data by remember { mutableStateOf<StorageSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<StorageCategory?>(null) }
    var pending by remember { mutableStateOf<StorageAction?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var historyVideos by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var historyLoaded by remember { mutableStateOf(false) }
    var selectedHistoryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var historySelectionMode by remember { mutableStateOf(false) }
    var pendingHistoryDelete by remember { mutableStateOf<HistoryDeleteRequest?>(null) }

    LaunchedEffect(db) {
        db.videoDao().all().collect { items ->
            historyVideos = items
            historyLoaded = true
            val existingKeys = items.map { it.key }.toSet()
            selectedHistoryKeys = selectedHistoryKeys.intersect(existingKeys)
        }
    }

    BackHandler { if (category != null) category = null else onBack() }
    val downloadBusy = PiperModels.hasActiveDownloads || piperDownloads > 0 ||
        ttsState.status in setOf(ModelStatus.DOWNLOADING, ModelStatus.EXTRACTING) ||
        asrState.status in setOf(ModelStatus.DOWNLOADING, ModelStatus.EXTRACTING)
    val playbackBusy = player.isPlaying ||
        player.phase in setOf(Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING, Phase.TRANSLATING, Phase.SYNTHESIZING) ||
        (player.phase == Phase.READY && player.totalLines > 0 && player.preparedCount < player.totalLines)
    val destructiveBusy = playbackBusy || downloadBusy

    LaunchedEffect(refresh) {
        scanning = true
        try {
            val snapshot = withContext(Dispatchers.IO) { scanStorage(ctx, db) }
            data = snapshot
            withContext(Dispatchers.IO) {
                val metrics = mapOf(
                    "扫描完成时间戳 ms" to snapshot.timestamp.toString(),
                    "应用数据总占用 bytes" to snapshot.total.toString(),
                    "临时缓存 bytes" to snapshot.cache.toString(),
                    "字幕缓存条数" to snapshot.subtitleCount.toString(),
                    "字幕缓存占用 bytes" to snapshot.subtitleBytes.toString(),
                    "视频历史条数" to snapshot.historyCount.toString(),
                    "收藏记录条数" to snapshot.favorites.toString(),
                    "模型目录占用 bytes" to snapshot.models.toString(),
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
    fun toggleHistorySelectAll() {
        val allKeys = historyVideos.map { it.key }.toSet()
        selectedHistoryKeys = if (allKeys.isNotEmpty() && selectedHistoryKeys.containsAll(allKeys)) emptySet() else allKeys
    }
    fun requestHistoryDelete(keys: List<String>, clearAll: Boolean = false) {
        if (destructiveBusy || actionBusy || scanning) {
            message = "当前有播放、处理或模型下载任务，暂不能清理历史记录及其缓存。"
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
        if (destructiveBusy || actionBusy) {
            pendingHistoryDelete = null
            message = "当前有播放、处理或模型下载任务，暂不能清理历史记录及其缓存。"
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
                        "操作前临时缓存 bytes" to runCatching { AppCacheStoragePolicy.sizeBytes(ctx.cacheDir) }.getOrDefault(-1L).toString(),
                        "操作前日志记录条数" to runCatching { AppLogStore.readRecent(ctx, 800).size }.getOrDefault(-1).toString(),
                        "是否播放/处理中" to playbackBusy.toString(),
                        "是否模型下载中" to downloadBusy.toString(),
                    ),
                )
                runCatching {
                    when (action) {
                        StorageAction.CACHE -> {
                            val before = AppCacheStoragePolicy.sizeBytes(ctx.cacheDir).coerceAtLeast(0L)
                            val ok = AppCacheStoragePolicy.clear(ctx.cacheDir)
                            val after = AppCacheStoragePolicy.sizeBytes(ctx.cacheDir).coerceAtLeast(0L)
                            val freed = (before - after).coerceAtLeast(0L)
                            if (ok) "临时缓存清理完成，实际释放 ${bytesLabel(freed)}。"
                            else "部分缓存仍被占用，实际释放 ${bytesLabel(freed)}；请停止相关任务后重试。"
                        }
                        StorageAction.SUBTITLES -> {
                            val count = db.subtitleCacheDao().countEntries()
                            db.subtitleCacheDao().clear()
                            "已清理 $count 条字幕与翻译缓存。历史记录和源视频不受影响；以后可能需要重新获取或翻译字幕。"
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
                        "当前临时缓存 bytes" to runCatching { AppCacheStoragePolicy.sizeBytes(ctx.cacheDir) }.getOrDefault(-1L).toString(),
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (category != null) category = null else onBack() }) {
                Icon(Icons.Filled.ArrowBack, "返回", tint = OnBg)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(category?.title ?: "存储管理", color = OnBg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (category == null) "本地数据总览 · 不会自动上传" else "分类占用与可执行操作", color = SubText, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { refresh++ }, enabled = !scanning && !actionBusy) {
                Icon(Icons.Filled.Refresh, "重新统计", tint = BrandBright)
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
                        loaded = historyLoaded,
                        busy = destructiveBusy || scanning,
                        actionBusy = actionBusy,
                        selectionMode = historySelectionMode,
                        selectedKeys = selectedHistoryKeys,
                        onToggleSelectionMode = {
                            val nextMode = !historySelectionMode
                            historySelectionMode = nextMode
                            if (!nextMode) selectedHistoryKeys = emptySet()
                        },
                        onToggleSelection = ::toggleHistorySelection,
                        onToggleSelectAll = ::toggleHistorySelectAll,
                        onDeleteSingle = { requestHistoryDelete(listOf(it)) },
                        onDeleteSelected = { requestHistoryDelete(selectedHistoryKeys.toList()) },
                        onDeleteAll = { requestHistoryDelete(historyVideos.map { it.key }, clearAll = true) },
                        onPlay = onPlay,
                    )
                } else {
                    StorageDetail(category!!, data, scanning, destructiveBusy, actionBusy, ::requestClear, onOpenLogs)
                }
            }
        }
    }

    pendingHistoryDelete?.let { request ->
        val description = when {
            request.clearAll -> "将删除全部视频历史（包括收藏），并清理应用管理的字幕/翻译、配音 PCM、转写音轨和 HLS 分段缓存。手机原始视频和已下载模型不会删除。"
            request.keys.size == 1 -> "将删除这条视频历史及其字幕/翻译、配音 PCM、转写音轨和 HLS 分段缓存。手机原始视频与已下载模型会保留。"
            else -> "将删除所选的 ${request.keys.size} 条视频历史及各自关联缓存。手机原始视频与已下载模型会保留。"
        }
        AlertDialog(
            onDismissRequest = { if (!actionBusy) pendingHistoryDelete = null },
            title = { Text(if (request.clearAll) "清除全部视频历史？" else if (request.keys.size == 1) "删除这条视频历史？" else "删除所选历史？", color = OnBg) },
            text = { Text(description, color = SubText) },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy && !destructiveBusy,
                    onClick = { executeHistoryDelete(request) },
                ) { Text(if (actionBusy) "处理中…" else "确认删除", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { if (!actionBusy) pendingHistoryDelete = null }) { Text("取消", color = SubText) } },
            containerColor = SurfaceDark,
        )
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!actionBusy) pending = null },
            title = { Text(action.title + "？", color = OnBg) },
            text = {
                Text(when (action) {
                    StorageAction.CACHE -> "只删除应用标记为可清理的配音 PCM、转写临时音频和 HLS 分段缓存，不会删除数据库、字幕缓存或已下载模型。"
                    StorageAction.SUBTITLES -> "将清空字幕与翻译缓存。视频历史、收藏和原始视频不会删除；以后可能需要重新获取或翻译字幕。"
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
            Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, OutlineSoft)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("可识别的应用数据占用", color = SubText, style = MaterialTheme.typography.bodySmall)
                    Text(data?.let { bytesLabel(it.total) } ?: if (scanning) "统计中…" else "暂不可用", color = OnBg, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("总占用按应用私有文件、缓存目录和数据库文件统计；字幕缓存估算值不会重复加到总占用中。", color = SubText, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = OutlineSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StorageMetric("可清理缓存", data?.let { bytesLabel(it.cache) } ?: "—", Icons.Filled.FolderOpen, Modifier.weight(1f))
                        StorageMetric("模型目录", data?.let { bytesLabel(it.models) } ?: "—", Icons.Filled.Memory, Modifier.weight(1f))
                    }
                }
            }
        }
        item { Text("数据分类", color = OnBg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(StorageCategory.values().toList()) { item ->
            val value = when (item) {
                StorageCategory.CACHE -> data?.let { "可清理缓存 ${bytesLabel(it.cache)}" } ?: "统计中…"
                StorageCategory.SUBTITLES -> data?.let { "${it.subtitleCount} 条 · 内容估算 ${bytesLabel(it.subtitleBytes)}" } ?: "统计中…"
                StorageCategory.MODELS -> data?.let { "模型目录 ${bytesLabel(it.models)}" } ?: "统计中…"
                StorageCategory.HISTORY -> data?.let { "${it.historyCount} 条记录 · 收藏 ${it.favorites} 条" } ?: "统计中…"
                StorageCategory.DATABASE -> data?.let { "数据库文件 ${bytesLabel(it.database)}" } ?: "统计中…"
                StorageCategory.LOGS -> data?.let { "${it.logCount} 条可读记录 · ${bytesLabel(it.logs)}" } ?: "统计中…"
            }
            StorageRow(item, value) { onOpen(item) }
        }
        data?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
            item {
                NoticeBox("部分数据统计不完整：\n" + warnings.joinToString("\n"), true)
            }
        }
        data?.let { info ->
            item { Text("上次统计：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(info.timestamp))}", color = SubText, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun StorageDetail(
    category: StorageCategory, data: StorageSnapshot?, scanning: Boolean, busy: Boolean,
    actionBusy: Boolean, onAction: (StorageAction) -> Unit, onOpenLogs: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
        item {
            Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, OutlineSoft)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("当前占用", color = SubText, style = MaterialTheme.typography.bodySmall)
                    Text(primaryValue(category, data, scanning), color = OnBg, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(category.hint, color = SubText, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = OutlineSoft)
                    when (category) {
                        StorageCategory.CACHE -> {
                            Text("只清理应用标记的临时音频与 HLS 缓存；数据库、字幕缓存和模型文件不受影响。", color = SubText, style = MaterialTheme.typography.bodySmall)
                            if (busy) NoticeBox("播放、处理或模型下载期间不能清理。", true)
                            ActionButton(if (actionBusy) "清理中…" else "清理临时缓存", !busy && !actionBusy && !scanning) { onAction(StorageAction.CACHE) }
                        }
                        StorageCategory.SUBTITLES -> {
                            MetricLine("缓存条目", data?.subtitleCount?.toString() ?: "—")
                            MetricLine("字幕 JSON 内容估算", data?.let { bytesLabel(it.subtitleBytes) } ?: "—")
                            Text("清理只清空字幕缓存表，不删除视频历史或原始视频。以后可能需要重新获取或翻译字幕。", color = SubText, style = MaterialTheme.typography.bodySmall)
                            if (busy) NoticeBox("播放、处理字幕或下载模型期间不能清理。", true)
                            ActionButton(if (actionBusy) "清理中…" else "清理字幕与翻译缓存", !busy && !actionBusy && !scanning) { onAction(StorageAction.SUBTITLES) }
                        }
                        StorageCategory.MODELS -> {
                            MetricLine("模型目录总占用", data?.let { bytesLabel(it.models) } ?: "—")
                            MetricLine("Kokoro / Piper 配音目录", data?.let { bytesLabel(it.tts) } ?: "—")
                            MetricLine("Whisper 与共享 VAD", data?.let { bytesLabel(it.whisper) } ?: "—")
                            MetricLine("SenseVoice", data?.let { bytesLabel(it.senseVoice) } ?: "—")
                            HorizontalDivider(color = OutlineSoft)
                            ModelStatusLine("Kokoro 配音模型", data?.ttsReady == true)
                            ModelStatusLine("Whisper 转写模型", data?.whisperReady == true)
                            ModelStatusLine("SenseVoice 转写模型", data?.senseReady == true)
                            MetricLine("已安装 Piper 音色模型", data?.piperCount?.toString() ?: "—")
                            MetricLine("当前选择的本地转写模型", data?.selectedAsr ?: "—")
                            NoticeBox("此页展示模型占用与就绪状态，不直接删除模型，避免误删仍被引擎引用的文件。下载和切换仍使用设置中的模型管理。", false)
                        }
                        StorageCategory.HISTORY -> {
                            MetricLine("历史记录总数", data?.historyCount?.toString() ?: "—")
                            MetricLine("收藏记录", data?.favorites?.toString() ?: "—")
                            NoticeBox("请在视频历史列表中选择要清除的单条记录、多条记录或全部历史。", false)
                        }
                        StorageCategory.DATABASE -> {
                            MetricLine("数据库及附属文件", data?.let { bytesLabel(it.database) } ?: "—")
                            Text("数据库包含历史、字幕缓存和 API 配置。此页不提供删除整个数据库的操作；请在对应分类中执行定向清理。", color = SubText, style = MaterialTheme.typography.bodySmall)
                        }
                        StorageCategory.LOGS -> {
                            MetricLine("可读取日志条数（最多 800）", data?.logCount?.toString() ?: "—")
                            MetricLine("日志与异常文件占用", data?.let { bytesLabel(it.logs) } ?: "—")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f), enabled = !actionBusy, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright)) { Text("查看 / 导出日志") }
                                OutlinedButton(onClick = { onAction(StorageAction.LOGS) }, modifier = Modifier.weight(1f), enabled = !actionBusy && !scanning, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed)) { Text("清理日志") }
                            }
                            Text("需要保留的故障详情请先导出。清理日志不会影响视频、字幕和模型。", color = SubText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun StorageHistoryDetail(
    videos: List<VideoEntity>,
    loaded: Boolean,
    busy: Boolean,
    actionBusy: Boolean,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    onToggleSelectionMode: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDeleteSingle: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
) {
    val allSelected = videos.isNotEmpty() && videos.all { it.key in selectedKeys }
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = PaddingValues(bottom = 14.dp),
    ) {
        item {
            Surface(
                Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, OutlineSoft),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("视频历史记录", color = OnBg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (loaded) "${videos.size} 条记录 · 收藏 ${videos.count { it.isFavorite }} 条" else "正在加载记录…",
                                color = SubText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            enabled = !busy && !actionBusy && videos.isNotEmpty(),
                            onClick = onToggleSelectionMode,
                        ) { Text(if (selectionMode) "取消多选" else "多选") }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectionMode) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !busy && !actionBusy && videos.isNotEmpty(),
                                onClick = onToggleSelectAll,
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright),
                            ) { Text(if (allSelected) "取消全选" else "全选") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = !busy && !actionBusy && selectedKeys.isNotEmpty(),
                                onClick = onDeleteSelected,
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            ) { Text("删除所选（${selectedKeys.size}）") }
                        } else {
                            Text(
                                "可单条删除，也可多选或一次清空全部记录",
                                modifier = Modifier.weight(1f),
                                color = SubText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            enabled = !busy && !actionBusy && videos.isNotEmpty(),
                            onClick = onDeleteAll,
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text("全部清除") }
                    }
                    Text(
                        "清除历史时会同步删除对应的字幕/翻译、配音及转写音轨缓存；手机原始视频和已下载模型会保留。",
                        color = SubText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (busy) Text("播放、处理或模型下载期间暂不能清理。", color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!loaded) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandBright, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                }
            }
        } else if (videos.isEmpty()) {
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
                        Text("还没有视频历史记录", color = OnBg, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else {
            items(videos, key = { it.key }) { video ->
                StorageHistoryVideoRow(
                    video = video,
                    selected = video.key in selectedKeys,
                    selectionMode = selectionMode,
                    enabled = !busy && !actionBusy,
                    onClick = {
                        if (selectionMode) onToggleSelection(video.key)
                        else onPlay(video.toHistoryPlayRequest())
                    },
                    onToggleSelection = { onToggleSelection(video.key) },
                    onDelete = { onDeleteSingle(video.key) },
                )
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
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (selectionMode) "选择记录" else "继续观看", color = BrandBright, style = MaterialTheme.typography.labelLarge)
                if (video.isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Star, contentDescription = "已收藏", tint = Color(0xFFE3A008), modifier = Modifier.size(14.dp))
                }
            }
        }
        if (!selectionMode) {
            IconButton(enabled = enabled, onClick = onDelete) {
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
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick), color = Color.White, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, OutlineSoft)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFFF0F4FC)) {
                Box(contentAlignment = Alignment.Center) { Icon(image, null, tint = BrandBright, modifier = Modifier.size(22.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(category.title, color = OnBg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(category.hint, color = SubText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        warnings += "$label 统计失败：${it.javaClass.simpleName}"; -1L
    }
    val cache = runCatching { AppCacheStoragePolicy.sizeBytes(context.cacheDir) }.getOrElse {
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
    val models = measured("模型目录", File(context.filesDir, "models"))
    val tts = measured("配音模型目录", ModelFiles.ttsDir(context))
    val whisper = measured("Whisper 模型目录", ModelFiles.asrDir(context))
    val sense = measured("SenseVoice 模型目录", ModelFiles.senseVoiceDir(context))
    val piperCount = runCatching { PiperModels.installedIds(context).size }.getOrElse {
        warnings += "Piper 模型状态读取失败：${it.javaClass.simpleName}"; -1
    }
    val ttsReady = runCatching { ModelFiles.ttsReady(context) }.getOrDefault(false)
    val whisperReady = runCatching { ModelFiles.whisperReady(context) }.getOrDefault(false)
    val senseReady = runCatching { ModelFiles.senseVoiceReady(context) }.getOrDefault(false)
    val selectedAsr = ModelFiles.localAsrModelDisplayName(ModelFiles.selectedLocalAsrModelId(context))
    val dbFile = context.getDatabasePath("translatetube.db")
    val databaseBytes = runCatching {
        dbFile.parentFile?.listFiles { f -> f.isFile && (f.name == dbFile.name || f.name.startsWith(dbFile.name + "-")) }
            ?.filter { it.isFile }?.fold(0L) { total, f -> addSaturated(total, f.length()) } ?: 0L
    }.getOrElse { warnings += "数据库文件统计失败：${it.javaClass.simpleName}"; -1L }
    val logA = measured("应用日志目录", File(context.filesDir, "app_logs"))
    val logB = measured("历史异常目录", File(context.filesDir, "crash"))
    val logBytes = if (logA < 0L || logB < 0L) -1L else addSaturated(logA, logB)
    val logCount = runCatching { AppLogStore.readRecent(context, 800).size }.getOrElse {
        warnings += "日志条数统计失败：${it.javaClass.simpleName}"; -1
    }
    val parts = listOf(
        measured("应用私有文件", context.filesDir),
        measured("应用缓存", context.cacheDir),
        databaseBytes,
        measured("无备份文件", context.noBackupFilesDir),
        measured("代码缓存", context.codeCacheDir),
    )
    val total = if (parts.any { it < 0L }) -1L else parts.fold(0L, ::addSaturated)
    return StorageSnapshot(total, cache, subtitleCount, subtitleBytes, history, favorites, models, tts, whisper, sense,
        piperCount, ttsReady, whisperReady, senseReady, selectedAsr, databaseBytes, logBytes, logCount,
        System.currentTimeMillis(), warnings)
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
