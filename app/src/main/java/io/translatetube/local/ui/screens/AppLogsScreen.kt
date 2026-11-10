package io.translatetube.local.ui.screens

import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.translatetube.local.ui.theme.*
import io.translatetube.local.util.AppLogIncidentDocument
import io.translatetube.local.util.AppLogRecord
import io.translatetube.local.util.AppLogTitleResolver
import io.translatetube.local.util.AppLogStore
import io.translatetube.local.util.AppLogSearchEntry
import io.translatetube.local.util.AppLogSearchIndex
import io.translatetube.local.util.AppLogDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

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
    var filter by remember { mutableStateOf("全部") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedRecord by remember { mutableStateOf<AppLogRecord?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
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
    var showLogExportPreview by remember { mutableStateOf(false) }
    var activeLogExport by remember { mutableStateOf<AppLogRecord?>(null) }
    var requestedLogExport by remember { mutableStateOf<AppLogRecord?>(null) }
    var logExportSavedCount by remember { mutableIntStateOf(0) }
    var logExportTotalCount by remember { mutableIntStateOf(0) }

    BackHandler(enabled = showIncidentExportPicker) {
        showIncidentExportPicker = false
    }
    BackHandler(enabled = selectionMode && !showIncidentExportPicker && !showLogExportPreview && selectedRecord == null) {
        selectionMode = false
        selectedLogKeys = emptySet()
    }
    BackHandler(
        enabled = !showIncidentExportPicker && !showLogExportPreview && !selectionMode && selectedRecord == null && !showClearConfirm,
        onBack = onBack,
    )

    val incidentExportCandidates = remember(records, incidentExportQuery) {
        val query = incidentExportQuery.trim()
        records.asSequence()
            .filter { it.level == "ERROR" || it.level == "FATAL" }
            .sortedWith(
                compareBy<AppLogRecord> { if (it.level == "FATAL") 0 else 1 }
                    .thenByDescending { it.timestampMs }
                    .thenByDescending { it.sequence },
            )
            .filter { record ->
                query.isBlank() ||
                    record.tag.contains(query, ignoreCase = true) ||
                    AppLogTitleResolver.title(record.tag, record.message).contains(query, ignoreCase = true) ||
                    record.message.contains(query, ignoreCase = true) ||
                    record.errorClass.contains(query, ignoreCase = true) ||
                    record.rootCauseMessage.contains(query, ignoreCase = true) ||
                    record.firstAppStackFrame.contains(query, ignoreCase = true) ||
                    record.issueKey.contains(query, ignoreCase = true) ||
                    record.eventId.contains(query, ignoreCase = true)
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
                        runCatching {
                            DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                        }
                    }
                }
                if (result.isSuccess) {
                    val savedKey = logRecordSelectionKey(target)
                    val remaining = pendingLogExports.filterNot {
                        logRecordSelectionKey(it) == savedKey
                    }
                    pendingLogExports = remaining
                    selectedLogKeys = selectedLogKeys - savedKey
                    logExportSavedCount += 1
                    if (remaining.isEmpty()) {
                        exporting = false
                        showLogExportPreview = false
                        selectionMode = false
                        selectedLogKeys = emptySet()
                        message = "已分别保存 ${logExportSavedCount} 个独立日志文件"
                    } else {
                        // Continue with the next item; each item gets its own Android
                        // Save As screen and its own suggested title-based filename.
                        exporting = true
                        requestedLogExport = remaining.first()
                        message = "已保存 ${logExportSavedCount} / ${logExportTotalCount} 个文件"
                    }
                } else {
                    exporting = false
                    message = "文件保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}；可重试当前日志"
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
    }

    LaunchedEffect(records) {
        val sourceRecords = records
        val indexed = AppLogSearchIndex.build(sourceRecords)
        if (sourceRecords === records) {
            searchSnapshot = AppLogSearchSnapshot(sourceRecords, indexed)
        }
    }

    LaunchedEffect(records, filter, searchQuery, searchSnapshot) {
        val snapshot = searchSnapshot
        if (snapshot.sourceRecords !== records) return@LaunchedEffect

        // Keep the text field fully controlled by the raw input. Only list filtering is
        // coalesced, so rapid typing/deletion never waits for the search result to render.
        if (searchQuery.isNotBlank()) delay(120L)
        visibleEntries = AppLogSearchIndex.search(
            entries = snapshot.entries,
            filter = filter,
            query = searchQuery,
        )
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
                        selectionMode = false
                        selectedLogKeys = emptySet()
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${records.size} 条记录",
                color = Color(0xFF202124),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "错误 $errorCount  ·  警告 $warningCount  ·  闪退 $crashCount",
                color = Color(0xFF737980),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }

        if (!writerHealth.isHealthy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFFF1F0))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFB42318),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "日志保存异常 · 累计失败 ${writerHealth.failedWriteCount} 次",
                        color = Color(0xFFB42318),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (writerHealth.lastFailureDescription.isNotBlank()) {
                        Text(
                            writerHealth.lastFailureDescription,
                            color = Color(0xFF7A271A),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("搜索日志、模块或关键词", maxLines = 1) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF8A9099))
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    TextButton(onClick = { searchQuery = "" }) { Text("清除") }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BrandBright,
                unfocusedBorderColor = Color(0xFFE0E3E7),
                focusedContainerColor = Color(0xFFFAFAFB),
                unfocusedContainerColor = Color(0xFFFAFAFB),
                focusedTextColor = Color(0xFF202124),
                unfocusedTextColor = Color(0xFF202124),
                focusedPlaceholderColor = Color(0xFF8A9099),
                unfocusedPlaceholderColor = Color(0xFF8A9099),
                cursorColor = Color(0xFF202124),
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                FilterChip(
                    selected = filter == name,
                    onClick = { filter = name },
                    label = { Text("$name $count", maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandBright,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                        containerColor = Color(0xFFF3F4F6),
                        labelColor = Color(0xFF454B53),
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == name,
                        borderColor = Color(0xFFE7E9EC),
                        selectedBorderColor = BrandBright,
                    ),
                )
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

        message?.let { text ->
            Text(
                text,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                color = if (text.startsWith("导出失败") || text.startsWith("日志导出失败") || text.contains("条失败") || text.startsWith("清理失败")) {
                    Color(0xFFB42318)
                } else {
                    Color(0xFF357A52)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
                }
            }
        } else {
            LazyColumn(
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
                        onClick = {
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
                    onClick = {
                        selectionMode = false
                        selectedLogKeys = emptySet()
                    },
                ) {
                    Text("取消")
                }
                Button(
                    enabled = selectedLogRecords.isNotEmpty() && !exporting,
                    onClick = {
                        pendingLogExports = selectedLogRecords.sortedBy { it.timestampMs }
                        logExportSavedCount = 0
                        logExportTotalCount = pendingLogExports.size
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
                    Text("导出所选")
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
                                "${logExportSavedCount}/${logExportTotalCount}"
                            } else {
                                "${pendingLogExports.size} 个文件"
                            },
                            color = Color(0xFF68707A),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    HorizontalDivider(color = Color(0xFFE9EAEC))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = pendingLogExports,
                            key = { logRecordSelectionKey(it) },
                        ) { record ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFF8F9FB))
                                    .padding(horizontal = 11.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    AppLogTitleResolver.title(record.tag, record.message),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF202124),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    softWrap = true,
                                )
                                Text(
                                    logRecordFileName(record),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF68707A),
                                    style = MaterialTheme.typography.labelSmall,
                                    softWrap = true,
                                )
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
                            enabled = pendingLogExports.isNotEmpty() &&
                                !exporting &&
                                activeLogExport == null &&
                                requestedLogExport == null,
                            onClick = {
                                val next = pendingLogExports.firstOrNull() ?: return@Button
                                exporting = true
                                message = null
                                requestedLogExport = next
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
                            Text(if (logExportSavedCount > 0) "继续保存" else "开始保存")
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
                    AppLogTitleResolver.title(record.tag, record.message),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )

                Text(
                    AppLogStore.formatTime(record.timestampMs),
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
                    record.tag,
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
                            rootCause,
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

private fun isRecoverableLevel(level: String): Boolean =
    level == "WARN" || level == "ERROR" || level == "FATAL"

private fun levelColor(level: String): Color = when (level) {
    "FATAL" -> Color(0xFFB42318)
    "ERROR" -> Color(0xFFC0392B)
    "WARN" -> Color(0xFFB7791F)
    else -> Color(0xFF64748B)
}
