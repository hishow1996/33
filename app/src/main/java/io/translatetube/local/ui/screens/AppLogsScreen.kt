package io.translatetube.local.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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

    BackHandler(enabled = showIncidentExportPicker) {
        showIncidentExportPicker = false
    }
    BackHandler(enabled = selectionMode && !showIncidentExportPicker && selectedRecord == null) {
        selectionMode = false
        selectedLogKeys = emptySet()
    }
    BackHandler(
        enabled = !showIncidentExportPicker && !selectionMode && selectedRecord == null && !showClearConfirm,
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

    val directoryExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val targets = pendingLogExports
        pendingLogExports = emptyList()
        if (treeUri != null && targets.isNotEmpty()) {
            exporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    exportLogRecordsToDirectory(ctx.applicationContext, treeUri, targets)
                }
                selectedLogKeys = selectedLogKeys - result.savedKeys
                when {
                    result.setupError != null -> {
                        message = "日志导出失败：${result.setupError}"
                    }
                    result.failedFiles.isEmpty() -> {
                        message = "已分别保存 ${result.savedKeys.size} 个独立日志文件"
                        selectedLogKeys = emptySet()
                        selectionMode = false
                    }
                    result.savedKeys.isEmpty() -> {
                        message = "日志导出失败：${result.failedFiles.size} 条日志未能保存，已保留选择"
                    }
                    else -> {
                        message = "已保存 ${result.savedKeys.size} 个独立文件，${result.failedFiles.size} 条失败；失败项已保留"
                    }
                }
                exporting = false
            }
        }
        // Canceling the folder picker leaves the current selection untouched.
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
                        directoryExportLauncher.launch(null)
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
    val module = record.tag
        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
        .trim('_')
        .take(28)
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
    return "日志_${module}_${level}_${time}_${identity}.txt"
}

private data class LogDirectoryExportResult(
    val savedKeys: Set<String>,
    val failedFiles: List<String>,
    val setupError: String? = null,
)

private fun exportLogRecordsToDirectory(
    context: android.content.Context,
    treeUri: Uri,
    records: List<AppLogRecord>,
): LogDirectoryExportResult {
    val resolver = context.contentResolver
    runCatching {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
    val parentUri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
    }.getOrElse { failure ->
        return LogDirectoryExportResult(
            savedKeys = emptySet(),
            failedFiles = records.map(::logRecordFileName),
            setupError = failure.message ?: "无法访问所选文件夹",
        )
    }

    val savedKeys = linkedSetOf<String>()
    val failedFiles = mutableListOf<String>()
    records.forEach { record ->
        val fileName = logRecordFileName(record)
        val saveResult = runCatching {
            val documentUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                "text/plain",
                fileName,
            ) ?: error("无法在所选文件夹创建文件")
            var completed = false
            try {
                val output = resolver.openOutputStream(documentUri, "wt")
                    ?: error("无法写入文件")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(AppLogStore.exportRecordFullText(record))
                    writer.flush()
                }
                completed = true
            } finally {
                if (!completed) {
                    runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
                }
            }
        }
        if (saveResult.isSuccess) {
            savedKeys += logRecordSelectionKey(record)
        } else {
            val reason = saveResult.exceptionOrNull()?.message?.take(80).orEmpty()
            failedFiles += if (reason.isBlank()) fileName else "$fileName（$reason）"
        }
    }
    return LogDirectoryExportResult(savedKeys, failedFiles)
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
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(top = 0.dp),
                    colors = CheckboxDefaults.colors(checkedColor = BrandBright),
                )
            }
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        AppLogTitleResolver.title(record.tag, record.message),
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF202124),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        AppLogStore.formatTime(record.timestampMs),
                        color = Color(0xFF858B93),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        record.tag,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF68707A),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("·", color = Color(0xFFB1B5BB))
                    Text(
                        record.level,
                        color = color,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isRecoverableLevel(record.level) && record.issueKey.isNotBlank()) {
                        Text("·", color = Color(0xFFB1B5BB))
                        Text(
                            if (record.recoveredAtMs != null) "已恢复" else "未恢复",
                            color = if (record.recoveredAtMs != null) Color(0xFF247347) else Color(0xFFB42318),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (rootCause.isNotBlank() && (record.level == "ERROR" || record.level == "FATAL" || record.level == "WARN")) {
                    Text(
                        rootCause,
                        color = Color(0xFF626A74),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 39.dp),
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
