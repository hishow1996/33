package io.translatetube.local.ui.screens

import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.translatetube.local.ui.theme.*
import io.translatetube.local.util.AppLogIncidentDocument
import io.translatetube.local.util.AppLogRecord
import io.translatetube.local.util.AppLogTitleResolver
import io.translatetube.local.util.AppLogStore
import io.translatetube.local.util.AppLogSearchEntry
import io.translatetube.local.util.AppLogSearchIndex
import io.translatetube.local.util.AppLogSearchHighlight
import io.translatetube.local.util.AppLogSearchHistoryPolicy
import io.translatetube.local.util.AppLogSearchMemory
import io.translatetube.local.util.AppLogSearchQuery
import io.translatetube.local.util.AppLogSearchResult
import io.translatetube.local.util.AppLogTimeRange
import io.translatetube.local.util.AppLogDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import org.json.JSONArray

@Composable
fun AppLogsScreen(onBack: () -> Unit) {
    // Match the app-wide blue-and-white theme on the log screen.
    val logsColorScheme = MaterialTheme.colorScheme.copy(
        primary = BrandBright,
        onPrimary = Color.White,
        primaryContainer = SurfaceHigh,
        onPrimaryContainer = OnBg,
        secondary = Brand,
        onSecondary = Color.White,
        secondaryContainer = SelectedBlue,
        onSecondaryContainer = OnBg,
        tertiary = BrandBright,
        onTertiary = Color.White,
    )
    MaterialTheme(colorScheme = logsColorScheme) {
        AppLogsScreenContent(onBack)
    }
}

@Composable
private fun AppLogsScreenContent(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<AppLogRecord>>(emptyList()) }
    // 搜索/筛选状态在进程内保留：点进某条日志再返回不丢。
    var filter by remember { mutableStateOf(AppLogSearchMemory.levelFilter) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedRecord by remember { mutableStateOf<AppLogRecord?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(AppLogSearchMemory.query) }
    var moduleFilter by remember { mutableStateOf(AppLogSearchMemory.module) }
    var timeRange by remember { mutableStateOf(AppLogSearchMemory.timeRange) }
    var searchResult by remember { mutableStateOf<AppLogSearchResult?>(null) }
    val searchPreferences = remember(ctx) {
        ctx.getSharedPreferences("app_log_search", android.content.Context.MODE_PRIVATE)
    }
    var searchHistory by remember {
        mutableStateOf(AppLogSearchHistoryPolicy.decode(searchPreferences.getString("history", null)))
    }
    var showModuleMenu by remember { mutableStateOf(false) }
    var showTimeRangeMenu by remember { mutableStateOf(false) }
    var showAdvancedFilters by remember { mutableStateOf(false) }
    var topControlsExpanded by remember { mutableStateOf(true) }
    var searchHasFocus by remember { mutableStateOf(false) }
    var requestSearchFocus by remember { mutableStateOf(false) }
    var showWriterHealthDetails by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val logListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    fun rememberSearch(query: String) {
        val updated = AppLogSearchHistoryPolicy.push(searchHistory, query)
        if (updated != searchHistory) {
            searchHistory = updated
            searchPreferences.edit()
                .putString("history", AppLogSearchHistoryPolicy.encode(updated))
                .apply()
        }
    }
    var searchSnapshot by remember {
        mutableStateOf(AppLogSearchSnapshot(emptyList(), emptyList()))
    }
    var visibleEntries by remember { mutableStateOf<List<AppLogSearchEntry>>(emptyList()) }
    var writerHealth by remember { mutableStateOf(AppLogStore.writerHealth()) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showIncidentExportPicker by remember { mutableStateOf(false) }
    var incidentExportQuery by remember { mutableStateOf("") }
    var selectedIncidentForExport by remember { mutableStateOf<AppLogRecord?>(null) }
    var pendingIncidentExport by remember { mutableStateOf<AppLogRecord?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedLogKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingLogExports by remember { mutableStateOf<List<AppLogRecord>>(emptyList()) }
    var savedLogExportKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failedLogExportKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var skippedLogExportKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showLogExportPreview by remember { mutableStateOf(false) }
    var activeLogExport by remember { mutableStateOf<AppLogRecord?>(null) }
    var requestedLogExport by remember { mutableStateOf<AppLogRecord?>(null) }
    var logExportTotalCount by remember { mutableIntStateOf(0) }
    var exportQueueRestoreAttempted by remember { mutableStateOf(false) }
    val logExportPreferences = remember(ctx) {
        ctx.getSharedPreferences("app_log_export_queue", android.content.Context.MODE_PRIVATE)
    }

    fun finishOrRequestNextLogExport() {
        val next = pendingLogExports.firstOrNull { record ->
            val key = logRecordSelectionKey(record)
            key !in savedLogExportKeys &&
                key !in skippedLogExportKeys &&
                key !in failedLogExportKeys
        }
        if (next != null) {
            exporting = true
            requestedLogExport = next
            return
        }
        if (failedLogExportKeys.isNotEmpty()) {
            exporting = false
            message = "已保存 ${savedLogExportKeys.size} / ${logExportTotalCount} 个文件，${failedLogExportKeys.size} 个失败；可逐条重试或跳过"
            return
        }

        val savedCount = savedLogExportKeys.size
        val skippedCount = skippedLogExportKeys.size
        pendingLogExports = emptyList()
        savedLogExportKeys = emptySet()
        failedLogExportKeys = emptySet()
        skippedLogExportKeys = emptySet()
        activeLogExport = null
        requestedLogExport = null
        exporting = false
        showLogExportPreview = false
        selectionMode = false
        selectedLogKeys = emptySet()
        message = if (skippedCount == 0) {
            "已分别保存 $savedCount 个独立日志文件"
        } else {
            "已保存 $savedCount 个文件，跳过 $skippedCount 个文件"
        }
    }

    fun clearLogExportQueue() {
        pendingLogExports = emptyList()
        savedLogExportKeys = emptySet()
        failedLogExportKeys = emptySet()
        skippedLogExportKeys = emptySet()
        activeLogExport = null
        requestedLogExport = null
        logExportTotalCount = 0
        showLogExportPreview = false
        exporting = false
    }

    fun exitSelectionMode() {
        clearLogExportQueue()
        selectionMode = false
        selectedLogKeys = emptySet()
    }

    BackHandler(enabled = showIncidentExportPicker) {
        showIncidentExportPicker = false
    }
    BackHandler(enabled = selectionMode && !showIncidentExportPicker && !showLogExportPreview && selectedRecord == null) {
        exitSelectionMode()
    }
    BackHandler(
        enabled = !showIncidentExportPicker && !showLogExportPreview && !selectionMode && selectedRecord == null && !showClearConfirm,
        onBack = onBack,
    )

    val incidentExportCandidates = remember(records, incidentExportQuery) {
        // 与日志页主搜索同一套规则：空格多词、-词 排除、"短语"、可搜时间。
        val query = AppLogSearchQuery.parse(incidentExportQuery)
        records.asSequence()
            .filter { it.level == "ERROR" || it.level == "FATAL" }
            .sortedWith(
                compareBy<AppLogRecord> { if (it.level == "FATAL") 0 else 1 }
                    .thenByDescending { it.timestampMs }
                    .thenByDescending { it.sequence },
            )
            .filter { record ->
                query.isEmpty || query.matchesFields(
                    listOf(
                        record.tag,
                        AppLogTitleResolver.title(record.tag, record.message),
                        record.message,
                        record.errorClass,
                        record.rootCauseMessage,
                        record.firstAppStackFrame,
                        record.issueKey,
                        record.eventId,
                        AppLogStore.formatTime(record.timestampMs),
                    ).map { it.lowercase(Locale.ROOT) },
                )
            }
            .toList()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = pendingIncidentExport
        pendingIncidentExport = null
        if (uri != null && target != null) {
            exporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val report = AppLogStore.exportIncidentFullText(ctx.applicationContext, target)
                        val output = ctx.contentResolver.openOutputStream(uri, "wt")
                            ?: error("无法打开所选文件")
                        output.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(report)
                            writer.flush()
                        }
                    }
                }
                if (result.isSuccess) {
                    selectedIncidentForExport = null
                    message = "单条 AI 诊断报告已保存"
                } else {
                    selectedIncidentForExport = target
                    incidentExportQuery = ""
                    showIncidentExportPicker = true
                    message = "报告保存失败，已保留所选错误，可重新导出：${result.exceptionOrNull()?.message ?: "未知错误"}"
                }
                exporting = false
            }
        }
        // A null URI means the user canceled the system picker. Keep the selected event so
        // reopening the export menu restores the same selection; cancellation is not a failure.
    }

    val logRecordExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = activeLogExport
        activeLogExport = null
        if (uri == null || target == null) {
            exporting = false
            message = "已取消保存，未完成的日志仍保留在导出列表中"
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolver = ctx.contentResolver
                        val output = resolver.openOutputStream(uri, "wt")
                            ?: error("无法打开所选文件")
                        output.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(AppLogStore.exportRecordFullText(target))
                            writer.flush()
                        }
                    }.onFailure {
                        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                    }
                }
                val key = logRecordSelectionKey(target)
                if (result.isSuccess) {
                    savedLogExportKeys = savedLogExportKeys + key
                    failedLogExportKeys = failedLogExportKeys - key
                    selectedLogKeys = selectedLogKeys - key
                    finishOrRequestNextLogExport()
                } else {
                    failedLogExportKeys = failedLogExportKeys + key
                    exporting = false
                    message = "文件保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}；可逐条重试或跳过"
                }
            }
        }
    }

    LaunchedEffect(requestedLogExport) {
        val target = requestedLogExport ?: return@LaunchedEffect
        requestedLogExport = null
        activeLogExport = target
        logRecordExportLauncher.launch(logRecordFileName(target))
    }

    LaunchedEffect(refreshKey) {
        val loaded = withContext(Dispatchers.IO) {
            AppLogStore.readRecent(ctx.applicationContext, 800) to AppLogStore.writerHealth()
        }
        records = loaded.first
        selectedLogKeys = selectedLogKeys.intersect(loaded.first.map(::logRecordSelectionKey).toSet())
        writerHealth = loaded.second

        if (!exportQueueRestoreAttempted) {
            val available = loaded.first.associateBy(::logRecordSelectionKey)
            val queueKeys = readLogExportKeys(logExportPreferences.getString("queue", null))
            val restoredQueue = queueKeys.mapNotNull(available::get)
            val restoredKeys = restoredQueue.map(::logRecordSelectionKey).toSet()
            if (restoredQueue.isNotEmpty()) {
                pendingLogExports = restoredQueue
                savedLogExportKeys = readLogExportKeys(logExportPreferences.getString("saved", null)).toSet()
                    .intersect(restoredKeys)
                failedLogExportKeys = readLogExportKeys(logExportPreferences.getString("failed", null)).toSet()
                    .intersect(restoredKeys)
                skippedLogExportKeys = readLogExportKeys(logExportPreferences.getString("skipped", null)).toSet()
                    .intersect(restoredKeys)
                logExportTotalCount = restoredQueue.size
            } else if (queueKeys.isNotEmpty()) {
                logExportPreferences.edit().clear().apply()
            }
            exportQueueRestoreAttempted = true
        }
    }

    LaunchedEffect(
        exportQueueRestoreAttempted,
        pendingLogExports,
        savedLogExportKeys,
        failedLogExportKeys,
        skippedLogExportKeys,
    ) {
        if (!exportQueueRestoreAttempted) return@LaunchedEffect
        if (pendingLogExports.isEmpty()) {
            logExportPreferences.edit().clear().apply()
        } else {
            logExportPreferences.edit()
                .putString("queue", encodeLogExportKeys(pendingLogExports.map(::logRecordSelectionKey)))
                .putString("saved", encodeLogExportKeys(savedLogExportKeys))
                .putString("failed", encodeLogExportKeys(failedLogExportKeys))
                .putString("skipped", encodeLogExportKeys(skippedLogExportKeys))
                .apply()
        }
    }

    LaunchedEffect(records) {
        val sourceRecords = records
        val indexed = AppLogSearchIndex.build(sourceRecords)
        if (sourceRecords === records) {
            searchSnapshot = AppLogSearchSnapshot(sourceRecords, indexed)
        }
    }

    LaunchedEffect(filter, searchQuery, moduleFilter, timeRange) {
        AppLogSearchMemory.levelFilter = filter
        AppLogSearchMemory.query = searchQuery
        AppLogSearchMemory.module = moduleFilter
        AppLogSearchMemory.timeRange = timeRange
    }

    LaunchedEffect(logListState, selectionMode, searchHasFocus, showAdvancedFilters) {
        if (selectionMode || searchHasFocus || showAdvancedFilters) {
            topControlsExpanded = true
            return@LaunchedEffect
        }
        var previousIndex = logListState.firstVisibleItemIndex
        var previousOffset = logListState.firstVisibleItemScrollOffset
        var lastDirection = 0
        var accumulatedDistance = 0
        snapshotFlow {
            logListState.firstVisibleItemIndex to logListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val delta = when {
                index > previousIndex -> 96
                index < previousIndex -> -96
                else -> offset - previousOffset
            }
            if (delta != 0) {
                val direction = delta.compareTo(0)
                accumulatedDistance = if (direction == lastDirection) {
                    accumulatedDistance + abs(delta)
                } else {
                    abs(delta)
                }
                lastDirection = direction

                when {
                    direction > 0 && accumulatedDistance >= 80 &&
                        (index > 0 || offset >= 96) -> topControlsExpanded = false
                    direction < 0 && accumulatedDistance >= 56 -> topControlsExpanded = true
                }
            }
            if (index == 0 && offset == 0) {
                topControlsExpanded = true
                accumulatedDistance = 0
                lastDirection = 0
            }
            previousIndex = index
            previousOffset = offset
        }
    }

    LaunchedEffect(topControlsExpanded, requestSearchFocus) {
        if (topControlsExpanded && requestSearchFocus) {
            searchFocusRequester.requestFocus()
            requestSearchFocus = false
        }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        val isError = text.contains("失败") || text.startsWith("清理失败")
        snackbarHostState.showSnackbar(
            message = text,
            duration = if (isError) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (message == text) message = null
    }

    LaunchedEffect(records, filter, searchQuery, moduleFilter, timeRange, searchSnapshot) {
        val snapshot = searchSnapshot
        if (snapshot.sourceRecords !== records) return@LaunchedEffect

        // Keep the text field fully controlled by the raw input. Only list filtering is
        // coalesced, so rapid typing/deletion never waits for the search result to render.
        if (searchQuery.isNotBlank()) delay(120L)
        val result = AppLogSearchIndex.searchDetailed(
            entries = snapshot.entries,
            filter = filter,
            query = searchQuery,
            module = moduleFilter,
            sinceMs = timeRange.sinceMs(System.currentTimeMillis()),
        )
        searchResult = result
        visibleEntries = result.entries
    }

    val moduleOptions = remember(searchSnapshot) { AppLogSearchIndex.moduleCounts(searchSnapshot.entries) }
    val hasActiveSearch = searchQuery.isNotBlank() || moduleFilter.isNotBlank() ||
        timeRange != AppLogTimeRange.ALL || filter != "全部"
    fun resetSearchAndFilters() {
        searchQuery = ""
        moduleFilter = ""
        timeRange = AppLogTimeRange.ALL
        filter = "全部"
    }

    val filterCounts = remember(records) {
        LogFilterCounts(
            unresolved = records.count {
                isRecoverableLevel(it.level) && it.issueKey.isNotBlank() && it.recoveredAtMs == null
            },
            recovered = records.count {
                isRecoverableLevel(it.level) && it.issueKey.isNotBlank() && it.recoveredAtMs != null
            },
            errors = records.count { it.level == "ERROR" || it.level == "FATAL" },
            crashes = records.count {
                it.level == "FATAL" ||
                    it.tag.contains("Crash", ignoreCase = true) ||
                    it.tag == "PreviousCrash" ||
                    (it.tag == "ProcessExitInfo" && (it.level == "ERROR" || it.level == "FATAL"))
            },
            warnings = records.count { it.level == "WARN" },
        )
    }
    val unresolvedCount = filterCounts.unresolved
    val recoveredCount = filterCounts.recovered
    val errorCount = filterCounts.errors
    val crashCount = filterCounts.crashes
    val warningCount = filterCounts.warnings
    val selectedLogRecords = remember(records, selectedLogKeys) {
        records.filter { logRecordSelectionKey(it) in selectedLogKeys }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (selectionMode) {
                        exitSelectionMode()
                    } else {
                        onBack()
                    }
                },
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = if (selectionMode) "退出选择" else "返回", tint = Color(0xFF202124))
            }
            Text(
                if (selectionMode) "选择日志" else "应用日志",
                modifier = Modifier.weight(1f),
                color = Color(0xFF202124),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (!selectionMode) {
                if (!topControlsExpanded) {
                    IconButton(
                        onClick = {
                            topControlsExpanded = true
                            requestSearchFocus = true
                        },
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "展开搜索", tint = Color(0xFF4B5563))
                    }
                }
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新日志", tint = Color(0xFF4B5563))
                }
                Box {
                    IconButton(onClick = { showActionsMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", tint = Color(0xFF4B5563))
                    }
                    DropdownMenu(
                        expanded = showActionsMenu,
                        onDismissRequest = { showActionsMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (exporting) "正在导出…" else "导出日志") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                            enabled = !exporting && records.isNotEmpty(),
                            onClick = {
                                showActionsMenu = false
                                if (records.isEmpty()) {
                                    message = "暂无可导出的日志记录"
                                } else {
                                    selectionMode = true
                                    selectedLogKeys = emptySet()
                                    message = null
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空日志") },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = Color(0xFFB42318)) },
                            onClick = {
                                showActionsMenu = false
                                showClearConfirm = true
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFFE9EAEC), thickness = 1.dp)

        AnimatedVisibility(
            visible = topControlsExpanded || selectionMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, top = 4.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${records.size} 条",
                color = Color(0xFF202124),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (hasActiveSearch) {
                Text(
                    "· 找到 ${visibleEntries.size} 条",
                    color = Color(0xFF626A74),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "错$errorCount · 警$warningCount · 闪$crashCount",
                color = Color(0xFF737980),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            if (hasActiveSearch) {
                Text(
                    "重置",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { resetSearchAndFilters() }
                        .padding(horizontal = 5.dp, vertical = 5.dp),
                    color = BrandBright,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        val detailsOnlyCount = searchResult?.detailsOnlyCount ?: 0
        if (hasActiveSearch && detailsOnlyCount > 0 && searchSnapshot.sourceRecords === records) {
            Text(
                "其中 $detailsOnlyCount 条仅在详情中命中，排在后面",
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 2.dp),
                color = Color(0xFF737980),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!writerHealth.isHealthy) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { showWriterHealthDetails = !showWriterHealthDetails },
                shape = RoundedCornerShape(9.dp),
                color = Color(0xFFFFF1F0),
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFB42318),
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            "日志保存异常 · 失败 ${writerHealth.failedWriteCount} 次",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFB42318),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (showWriterHealthDetails) "收起" else "详情",
                            color = Color(0xFFB42318),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (showWriterHealthDetails && writerHealth.lastFailureDescription.isNotBlank()) {
                        Text(
                            writerHealth.lastFailureDescription,
                            color = Color(0xFF7A271A),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFFAFAFB),
            border = BorderStroke(
                1.dp,
                if (searchHasFocus) BrandBright else Color(0xFFE0E3E7),
            ),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color(0xFF8A9099),
                    modifier = Modifier.size(21.dp),
                )
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester)
                        .onFocusChanged { searchHasFocus = it.isFocused },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Color(0xFF202124),
                    ),
                    cursorBrush = SolidColor(Color(0xFF202124)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        rememberSearch(searchQuery)
                        focusManager.clearFocus()
                    }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isBlank()) {
                                Text(
                                    "搜索日志（支持多词、-排除、时间）",
                                    color = Color(0xFF8A9099),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (searchQuery.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { searchQuery = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清除搜索",
                            tint = Color(0xFF737980),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val filters = listOf(
                    "全部" to records.size,
                    "错误" to errorCount,
                    "闪退" to crashCount,
                    "警告" to warningCount,
                    "未恢复" to unresolvedCount,
                    "已恢复" to recoveredCount,
                )
                filters.forEach { (name, count) ->
                    val selected = filter == name
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { filter = name },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) BrandBright else Color(0xFFF3F4F6),
                        border = BorderStroke(
                            1.dp,
                            if (selected) BrandBright else Color(0xFFE7E9EC),
                        ),
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            "$name $count",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            color = if (selected) Color.White else Color(0xFF454B53),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.width(7.dp))
            val hasAdvancedFilter = moduleFilter.isNotBlank() || timeRange != AppLogTimeRange.ALL
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showAdvancedFilters = !showAdvancedFilters },
                shape = RoundedCornerShape(10.dp),
                color = if (hasAdvancedFilter) BrandBright else Color(0xFFF3F4F6),
                border = BorderStroke(
                    1.dp,
                    if (hasAdvancedFilter) BrandBright else Color(0xFFE7E9EC),
                ),
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "筛选",
                        color = if (hasAdvancedFilter) Color.White else Color(0xFF454B53),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (showAdvancedFilters) "⌃" else "⌄",
                        color = if (hasAdvancedFilter) Color.White else Color(0xFF737980),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        if (showAdvancedFilters) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showModuleMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            if (moduleFilter.isBlank()) "模块：全部" else "模块：$moduleFilter",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(
                        expanded = showModuleMenu,
                        onDismissRequest = { showModuleMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部模块") },
                            onClick = {
                                moduleFilter = ""
                                showModuleMenu = false
                            },
                        )
                        moduleOptions.take(40).forEach { (label, count) ->
                            DropdownMenuItem(
                                text = { Text("$label  $count") },
                                onClick = {
                                    moduleFilter = label
                                    showModuleMenu = false
                                },
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { showTimeRangeMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "时间：${timeRange.label}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(
                        expanded = showTimeRangeMenu,
                        onDismissRequest = { showTimeRangeMenu = false },
                    ) {
                        AppLogTimeRange.values().forEach { range ->
                            DropdownMenuItem(
                                text = { Text(range.label) },
                                onClick = {
                                    timeRange = range
                                    showTimeRangeMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }

        if (searchHasFocus && searchQuery.isBlank() && searchHistory.isNotEmpty() && !selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近搜索", color = Color(0xFF8A9099), style = MaterialTheme.typography.labelMedium)
                searchHistory.forEach { item ->
                    AssistChip(
                        onClick = {
                            searchQuery = item
                            focusManager.clearFocus()
                        },
                        label = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                TextButton(onClick = {
                    searchHistory = emptyList()
                    searchPreferences.edit().remove("history").apply()
                }) { Text("清空") }
            }
        }



            }
        }

        if (selectionMode) {
            val currentVisibleKeys = visibleEntries.map { logRecordSelectionKey(it.record) }.toSet()
            val allVisibleSelected = currentVisibleKeys.isNotEmpty() &&
                currentVisibleKeys.all { it in selectedLogKeys }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选 ${selectedLogRecords.size} 条",
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = currentVisibleKeys.isNotEmpty(),
                    onClick = {
                        selectedLogKeys = if (allVisibleSelected) {
                            selectedLogKeys - currentVisibleKeys
                        } else {
                            selectedLogKeys + currentVisibleKeys
                        }
                    },
                ) {
                    Text(if (allVisibleSelected) "取消当前全选" else "全选当前")
                }
                TextButton(onClick = { selectedLogKeys = emptySet() }) {
                    Text("清空选择")
                }
            }
        }

        if (pendingLogExports.isNotEmpty() && !selectionMode && !showLogExportPreview) {
            val remainingExports = pendingLogExports.filter {
                val key = logRecordSelectionKey(it)
                key !in savedLogExportKeys && key !in skippedLogExportKeys
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF1F6FF))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "上次有 ${remainingExports.size} 个日志未导出完成",
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        selectedLogKeys = remainingExports.map(::logRecordSelectionKey).toSet()
                        selectionMode = true
                        showLogExportPreview = true
                    },
                ) { Text("继续") }
                TextButton(onClick = { clearLogExportQueue() }) { Text("放弃") }
            }
        }


        if (visibleEntries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color(0xFF9AA0A6),
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        records.isEmpty() -> "暂无日志记录"
                        searchQuery.isNotBlank() -> "没有找到匹配的记录"
                        else -> "暂无符合条件的记录"
                    },
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (records.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { refreshKey++ }) { Text("重新读取") }
                } else if (hasActiveSearch) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { resetSearchAndFilters() }) { Text("重置筛选") }
                }
            }
        } else {
            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 2.dp, bottom = 20.dp),
            ) {
                items(
                    items = visibleEntries,
                    key = { entry ->
                        val record = entry.record
                        record.eventId.ifBlank {
                            listOf(record.timestampMs, record.sequence, record.tag, record.message.hashCode()).joinToString("-")
                        }
                    },
                ) { entry ->
                    val record = entry.record
                    val recordKey = logRecordSelectionKey(record)
                    LogRecordCard(
                        record = record,
                        diagnosis = entry.diagnosis,
                        selectionMode = selectionMode,
                        selected = recordKey in selectedLogKeys,
                        highlightTerms = searchResult?.query?.include.orEmpty(),
                        detailsOnly = searchResult?.isDetailsOnly(entry) == true,
                        onClick = {
                            if (!selectionMode) rememberSearch(searchQuery)
                            if (selectionMode) {
                                selectedLogKeys = if (recordKey in selectedLogKeys) {
                                    selectedLogKeys - recordKey
                                } else {
                                    selectedLogKeys + recordKey
                                }
                            } else {
                                selectedRecord = record
                            }
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )

        if (selectionMode) {
            HorizontalDivider(color = Color(0xFFE9EAEC), thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${selectedLogRecords.size} 条",
                    modifier = Modifier.padding(start = 4.dp),
                    color = Color(0xFF454B53),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                val singleSelectedError = selectedLogRecords.singleOrNull()
                    ?.takeIf { it.level == "ERROR" || it.level == "FATAL" }
                if (singleSelectedError != null) {
                    TextButton(
                        enabled = !exporting,
                        onClick = {
                            selectedIncidentForExport = singleSelectedError
                            incidentExportQuery = ""
                            showIncidentExportPicker = true
                        },
                    ) {
                        Text("AI 诊断")
                    }
                }
                TextButton(
                    enabled = !exporting,
                    onClick = { exitSelectionMode() },
                ) {
                    Text("取消")
                }
                Button(
                    enabled = (pendingLogExports.isNotEmpty() || selectedLogRecords.isNotEmpty()) && !exporting,
                    onClick = {
                        if (pendingLogExports.isEmpty()) {
                            val newQueue = selectedLogRecords.sortedWith(
                                compareByDescending<AppLogRecord> { it.timestampMs }
                                    .thenByDescending { it.sequence },
                            )
                            pendingLogExports = newQueue
                            savedLogExportKeys = emptySet()
                            failedLogExportKeys = emptySet()
                            skippedLogExportKeys = emptySet()
                            logExportTotalCount = newQueue.size
                        }
                        message = null
                        showLogExportPreview = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandBright,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (pendingLogExports.isNotEmpty()) "继续导出" else "导出所选")
                }
            }
        }
    }

    if (showLogExportPreview) {
        Dialog(
            onDismissRequest = {
                if (!exporting && activeLogExport == null && requestedLogExport == null) {
                    showLogExportPreview = false
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val completedCount = savedLogExportKeys.size + skippedLogExportKeys.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "导出日志",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF202124),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (logExportTotalCount > 0) {
                                "${completedCount}/${logExportTotalCount}"
                            } else {
                                "${pendingLogExports.size} 个文件"
                            },
                            color = Color(0xFF68707A),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    HorizontalDivider(color = Color(0xFFE9EAEC))
                    val progress = if (logExportTotalCount > 0) {
                        completedCount.toFloat() / logExportTotalCount.toFloat()
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE9EEF5)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(BrandBright),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = pendingLogExports,
                            key = { logRecordSelectionKey(it) },
                        ) { record ->
                            val recordKey = logRecordSelectionKey(record)
                            val isActive = activeLogExport?.let(::logRecordSelectionKey) == recordKey ||
                                requestedLogExport?.let(::logRecordSelectionKey) == recordKey
                            val statusText = when {
                                recordKey in savedLogExportKeys -> "已保存"
                                isActive -> "正在保存"
                                recordKey in failedLogExportKeys -> "保存失败"
                                recordKey in skippedLogExportKeys -> "已跳过"
                                else -> "待保存"
                            }
                            val statusColor = when {
                                recordKey in savedLogExportKeys -> Color(0xFF247347)
                                isActive -> BrandBright
                                recordKey in failedLogExportKeys -> Color(0xFFB42318)
                                recordKey in skippedLogExportKeys -> Color(0xFF858B93)
                                else -> Color(0xFF68707A)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFF8F9FB))
                                    .padding(horizontal = 11.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        AppLogTitleResolver.title(record.tag, record.message),
                                        modifier = Modifier.weight(1f),
                                        color = Color(0xFF202124),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        softWrap = true,
                                    )
                                    Text(
                                        statusText,
                                        color = statusColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Text(
                                    logRecordFileName(record),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF68707A),
                                    style = MaterialTheme.typography.labelSmall,
                                    softWrap = true,
                                )
                                if (recordKey in failedLogExportKeys && !exporting) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                failedLogExportKeys = failedLogExportKeys - recordKey
                                                exporting = true
                                                message = null
                                                requestedLogExport = record
                                            },
                                        ) { Text("重试") }
                                        TextButton(
                                            onClick = {
                                                skippedLogExportKeys = skippedLogExportKeys + recordKey
                                                failedLogExportKeys = failedLogExportKeys - recordKey
                                                selectedLogKeys = selectedLogKeys - recordKey
                                                finishOrRequestNextLogExport()
                                            },
                                        ) { Text("跳过") }
                                    }
                                }
                            }
                        }
                    }
                    message?.takeIf {
                        it.contains("取消保存") || it.contains("保存失败") || it.contains("已保存")
                    }?.let { status ->
                        Text(
                            status,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (status.contains("失败")) Color(0xFFB42318) else Color(0xFF357A52),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = !exporting && activeLogExport == null && requestedLogExport == null,
                            onClick = { showLogExportPreview = false },
                        ) {
                            Text("关闭")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = pendingLogExports.any { record ->
                                val key = logRecordSelectionKey(record)
                                key !in savedLogExportKeys &&
                                    key !in skippedLogExportKeys &&
                                    key !in failedLogExportKeys
                            } && !exporting && activeLogExport == null && requestedLogExport == null,
                            onClick = {
                                message = null
                                finishOrRequestNextLogExport()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandBright,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (completedCount > 0) "继续保存" else "开始保存")
                        }
                    }
                }
            }
        }
    }

    if (showIncidentExportPicker) {
        Dialog(
            onDismissRequest = {
                showIncidentExportPicker = false
                selectedIncidentForExport = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f))
                        .clickable {
                            showIncidentExportPicker = false
                            selectedIncidentForExport = null
                        },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(Color.White)
                        .navigationBarsPadding(),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 12.dp, bottom = 10.dp)
                            .size(width = 38.dp, height = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFD8DCE2)),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "选择错误",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF202124),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = {
                                showIncidentExportPicker = false
                                selectedIncidentForExport = null
                            },
                        ) { Text("关闭") }
                    }
                    OutlinedTextField(
                        value = incidentExportQuery,
                        onValueChange = { incidentExportQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("搜索模块、错误编号或内容", maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF8A9099))
                        },
                        trailingIcon = {
                            if (incidentExportQuery.isNotBlank()) {
                                TextButton(onClick = { incidentExportQuery = "" }) { Text("清除") }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandBright,
                            unfocusedBorderColor = Color(0xFFE0E3E7),
                            focusedContainerColor = Color(0xFFFAFAFB),
                            unfocusedContainerColor = Color(0xFFFAFAFB),
                            focusedTextColor = Color(0xFF202124),
                            unfocusedTextColor = Color(0xFF202124),
                            cursorColor = Color(0xFF202124),
                        ),
                    )
                    if (incidentExportCandidates.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Color(0xFF9AA0A6),
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (incidentExportQuery.isBlank()) "暂无可导出的错误记录" else "没有找到匹配的错误",
                                color = Color(0xFF202124),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = incidentExportCandidates,
                                key = {
                                    it.eventId.ifBlank {
                                        "${it.timestampMs}-${it.tag}-${it.message.hashCode()}"
                                    }
                                },
                            ) { record ->
                                IncidentExportRecordRow(
                                    record = record,
                                    selected = selectedIncidentForExport?.let { selected ->
                                        if (selected.eventId.isNotBlank()) {
                                            selected.eventId == record.eventId
                                        } else {
                                            selected.timestampMs == record.timestampMs &&
                                                selected.tag == record.tag &&
                                                selected.message == record.message
                                        }
                                    } == true,
                                    onClick = { selectedIncidentForExport = record },
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFE9EAEC), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                selectedIncidentForExport?.let { selected ->
                                    AppLogTitleResolver.title(selected.tag, selected.message) + " · " + selected.level
                                } ?: "请选择一条错误",
                                color = Color(0xFF202124),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            selectedIncidentForExport?.let { selected ->
                                Text(
                                    "${selected.tag} · ${AppLogStore.formatTime(selected.timestampMs)}",
                                    color = Color(0xFF737980),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Button(
                            enabled = selectedIncidentForExport != null && !exporting,
                            onClick = {
                                val target = selectedIncidentForExport ?: return@Button
                                pendingIncidentExport = target
                                showIncidentExportPicker = false
                                exportLauncher.launch(incidentReportFileName(target))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandBright,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("导出完整报告")
                        }
                    }
                }
            }
        }
    }

    selectedRecord?.let { record ->
        Dialog(
            onDismissRequest = { selectedRecord = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .systemBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedRecord = null }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回日志列表", tint = Color(0xFF202124))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "日志详情",
                            color = Color(0xFF202124),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${record.level} · ${AppLogTitleResolver.featureLabel(record.tag, record.message)} · ${record.tag}",
                            color = levelColor(record.level),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFFE9EAEC))

                val document = remember(record, records) {
                    AppLogIncidentDocument.build(record, records, AppLogStore.environmentSnapshot())
                }
                val documentText = remember(document) { document.toPlainText() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
                            document.sections().forEachIndexed { index, section ->
                                if (index == 0) {
                                    Text(
                                        section.content,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF202124),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                } else {
                                    IncidentDetailSection(section.title, section.content)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFE9EAEC))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { selectedRecord = null },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("关闭") }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val clipboard = ctx.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE,
                            ) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("AI 故障定位", documentText),
                            )
                            android.widget.Toast.makeText(
                                ctx,
                                "已复制",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandBright,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("复制")
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清理本地日志？", color = OnBg) },
            text = { Text("将删除已保存的运行日志和上次异常记录。此操作不能撤销，建议先导出。", color = SubText) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        val success = withContext(Dispatchers.IO) { AppLogStore.clear(ctx.applicationContext) }
                        message = if (success) "日志已清理" else "清理失败：部分文件无法删除"
                        refreshKey++
                    }
                }) { Text("确认清理", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
            containerColor = Color.White,
        )
    }
}

@Composable
private fun IncidentDetailSection(title: String, content: String) {
    if (content.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            title,
            color = Color(0xFF202124),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            content,
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF3F4650),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun logRecordSelectionKey(record: AppLogRecord): String =
    record.eventId.takeIf { it.isNotBlank() }?.let { "event:$it" }
        ?: listOf(
            "legacy",
            record.timestampMs,
            record.sequence,
            record.tag,
            record.message.hashCode(),
            record.details.hashCode(),
            record.thread,
        ).joinToString(":")


private fun readLogExportKeys(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}

private fun encodeLogExportKeys(keys: Iterable<String>): String =
    JSONArray().apply { keys.forEach { put(it) } }.toString()

private fun logRecordFileName(record: AppLogRecord): String {
    val rawTitle = AppLogTitleResolver.title(record.tag, record.message)
        .ifBlank { record.tag.ifBlank { "App" } }
    val safeTitle = rawTitle
        .replace(Regex("[\\u0000-\\u001F\\\\/:*?\"<>|]+"), "_")
        .replace(Regex("\\s+"), "_")
        .trim('_', '.')
        .take(48)
        .let { value ->
            if (value.isNotEmpty() && Character.isHighSurrogate(value.last())) {
                value.dropLast(1)
            } else {
                value
            }
        }
        .ifBlank { "App" }
    val level = record.level
        .filter { it.isLetterOrDigit() }
        .take(10)
        .ifBlank { "INFO" }
    val time = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        .format(Date(record.timestampMs))
    val identitySource = record.eventId.ifBlank {
        "${record.processId}_${record.sequence}_${record.timestampMs}_${record.message.hashCode()}_${record.tag.hashCode()}"
    }
    val identity = identitySource
        .filter { it.isLetterOrDigit() }
        .takeLast(16)
        .ifBlank { "event" }
    return "日志_${safeTitle}_${level}_${time}_${identity}.txt"
}

private fun incidentReportFileName(record: AppLogRecord): String {
    val module = record.tag
        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
        .trim('_')
        .take(36)
        .ifBlank { "Error" }
    val time = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        .format(Date(record.timestampMs))
    val level = record.level.filter { it.isLetterOrDigit() }.take(10).ifBlank { "ERROR" }
    val identitySource = record.eventId.ifBlank {
        "${record.processId}_${record.sequence}_${record.timestampMs}_${record.message.hashCode()}_${record.tag.hashCode()}"
    }
    val identity = identitySource
        .filter { it.isLetterOrDigit() }
        .takeLast(10)
        .ifBlank { "event" }
    return "AI诊断_${module}_${level}_${time}_${identity}.txt"
}

@Composable
private fun IncidentExportRecordRow(
    record: AppLogRecord,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val levelTint = if (record.level == "FATAL") Color(0xFFB42318) else Color(0xFFD97706)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) Color(0xFFF0F6FF) else Color.White,
        border = BorderStroke(
            1.dp,
            if (selected) BrandBright.copy(alpha = 0.8f) else Color(0xFFE7E9EC),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        AppLogTitleResolver.title(record.tag, record.message),
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF202124),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        color = if (record.level == "FATAL") Color(0xFFFFE8E6) else Color(0xFFFFF1D6),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            record.level,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            color = levelTint,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    "${record.tag} · ${AppLogStore.formatTime(record.timestampMs)}",
                    color = Color(0xFF737980),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LogSummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = OnBg,
) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, OutlineSoft),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = SubText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(value, color = valueColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class AppLogSearchSnapshot(
    val sourceRecords: List<AppLogRecord>,
    val entries: List<AppLogSearchEntry>,
)

private data class LogFilterCounts(
    val unresolved: Int,
    val recovered: Int,
    val errors: Int,
    val crashes: Int,
    val warnings: Int,
)

@Composable
private fun LogRecordCard(
    record: AppLogRecord,
    diagnosis: io.translatetube.local.util.AppLogDiagnosis? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    highlightTerms: List<String> = emptyList(),
    detailsOnly: Boolean = false,
    onClick: () -> Unit,
) {
    val color = levelColor(record.level)
    val resolvedDiagnosis = diagnosis ?: remember(record.details, record.tag, record.message) {
        AppLogDiagnostics.analyze(record)
    }
    val rootCause = resolvedDiagnosis.finding
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (selectionMode && selected) Color(0xFFF1F6FF) else Color.White,
                )
                .clickable(
                    role = if (selectionMode) Role.Checkbox else null,
                    onClick = onClick,
                )
                .semantics {
                    if (selectionMode) {
                        toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                        stateDescription = if (selected) "已选择" else "未选择"
                    }
                }
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Reserve the same 22dp leading column in both modes so every title starts
            // at the same horizontal position. The whole row remains the touch target.
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .padding(top = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selectionMode) {
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = if (selected) BrandBright else Color.White,
                        border = if (selected) null else BorderStroke(1.dp, Color(0xFF8793A1)),
                        tonalElevation = 0.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (selected) {
                                Text(
                                    "✓",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Keep the complete title. Normal soft wrapping also handles mixed
                // Chinese/English text, numbers and paths without a fixed line limit.
                Text(
                    highlightedLogText(AppLogTitleResolver.title(record.tag, record.message), highlightTerms),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )

                Text(
                    highlightedLogText(AppLogStore.formatTime(record.timestampMs), highlightTerms),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF858B93),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    softWrap = true,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )

                // Keep the module name on its own line so increased system font size
                // cannot squeeze the level or recovery state out of the row.
                Text(
                    highlightedLogText(record.tag, highlightTerms),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF68707A),
                    style = MaterialTheme.typography.labelMedium,
                    softWrap = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Surface(
                        color = color.copy(alpha = 0.09f),
                        shape = RoundedCornerShape(5.dp),
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            record.level,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = color,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (detailsOnly) {
                        Text(
                            "仅详情命中",
                            color = Color(0xFF1A5FD0),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (isRecoverableLevel(record.level) && record.issueKey.isNotBlank()) {
                        Text(
                            if (record.recoveredAtMs != null) "已恢复" else "未恢复",
                            color = if (record.recoveredAtMs != null) Color(0xFF247347) else Color(0xFFB42318),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                if (rootCause.isNotBlank() && (record.level == "ERROR" || record.level == "FATAL" || record.level == "WARN")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .width(2.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(color.copy(alpha = 0.35f)),
                        )
                        Text(
                            highlightedLogText(rootCause, highlightTerms),
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF626A74),
                            style = MaterialTheme.typography.bodySmall,
                            softWrap = true,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 52.dp),
            color = Color(0xFFF0F1F3),
            thickness = 1.dp,
        )
    }
}

/** 把命中的搜索词标成浅黄底，其余原样显示。 */
private fun highlightedLogText(text: String, terms: List<String>): AnnotatedString {
    val ranges = AppLogSearchHighlight.ranges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (range in ranges) {
            addStyle(
                SpanStyle(background = Color(0xFFFFE9A8), color = Color(0xFF202124)),
                range.first,
                range.last + 1,
            )
        }
    }
}

private fun isRecoverableLevel(level: String): Boolean =
    level == "WARN" || level == "ERROR" || level == "FATAL"

private fun levelColor(level: String): Color = when (level) {
    "FATAL" -> Color(0xFFB42318)
    "ERROR" -> Color(0xFFC0392B)
    "WARN" -> Color(0xFFB7791F)
    else -> Color(0xFF64748B)
}
