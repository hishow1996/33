package io.translatetube.local.ui.screens

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
import io.translatetube.local.util.AppLogRecord
import io.translatetube.local.util.AppLogTitleResolver
import io.translatetube.local.util.AppLogStore
import io.translatetube.local.util.AppLogDiagnostics
import io.translatetube.local.util.AppLogDiagnosticCoverage
import io.translatetube.local.util.AppLogDiagnosticCoveragePolicy
import kotlinx.coroutines.Dispatchers
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
    var writerHealth by remember { mutableStateOf(AppLogStore.writerHealth()) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showIncidentExportPicker by remember { mutableStateOf(false) }
    var incidentExportQuery by remember { mutableStateOf("") }
    var selectedIncidentForExport by remember { mutableStateOf<AppLogRecord?>(null) }
    var pendingIncidentExport by remember { mutableStateOf<AppLogRecord?>(null) }

    BackHandler(enabled = showIncidentExportPicker) {
        showIncidentExportPicker = false
    }
    BackHandler(
        enabled = !showIncidentExportPicker && selectedRecord == null && !showClearConfirm,
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

    LaunchedEffect(refreshKey) {
        val loaded = withContext(Dispatchers.IO) {
            AppLogStore.readRecent(ctx.applicationContext) to AppLogStore.writerHealth()
        }
        records = loaded.first
        writerHealth = loaded.second
    }

    val visibleRecords = remember(records, filter, searchQuery) {
        val levelFiltered = when (filter) {
            "错误" -> records.filter { it.level == "ERROR" || it.level == "FATAL" }
            "未恢复" -> records.filter {
                isRecoverableLevel(it.level) && it.issueKey.isNotBlank() && it.recoveredAtMs == null
            }
            "已恢复" -> records.filter {
                isRecoverableLevel(it.level) && it.issueKey.isNotBlank() && it.recoveredAtMs != null
            }
            "闪退" -> records.filter {
                it.level == "FATAL" ||
                    it.tag.contains("Crash", ignoreCase = true) ||
                    it.tag == "PreviousCrash" ||
                    (it.tag == "ProcessExitInfo" && (it.level == "ERROR" || it.level == "FATAL"))
            }
            "警告" -> records.filter { it.level == "WARN" }
            else -> records
        }
        val query = searchQuery.trim()
        if (query.isBlank()) levelFiltered else levelFiltered.filter { record ->
            val diagnosis = AppLogDiagnostics.analyze(record)
            record.tag.contains(query, ignoreCase = true) ||
                AppLogTitleResolver.featureLabel(record.tag, record.message).contains(query, ignoreCase = true) ||
                AppLogTitleResolver.title(record.tag, record.message).contains(query, ignoreCase = true) ||
                record.message.contains(query, ignoreCase = true) ||
                record.details.contains(query, ignoreCase = true) ||
                record.thread.contains(query, ignoreCase = true) ||
                record.eventId.contains(query, ignoreCase = true) ||
                record.sessionId.contains(query, ignoreCase = true) ||
                record.operationId.contains(query, ignoreCase = true) ||
                record.errorClass.contains(query, ignoreCase = true) ||
                record.rootCauseMessage.contains(query, ignoreCase = true) ||
                record.firstAppStackFrame.contains(query, ignoreCase = true) ||
                record.diagnosticContext.any { (key, value) ->
                    key.contains(query, ignoreCase = true) || value.contains(query, ignoreCase = true)
                } ||
                record.issueKey.contains(query, ignoreCase = true) ||
                record.resolvesIssueKey.contains(query, ignoreCase = true) ||
                diagnosis.finding.contains(query, ignoreCase = true) ||
                diagnosis.category.contains(query, ignoreCase = true)
        }
    }
    val unresolvedCount = records.count {
        isRecoverableLevel(it.level) && it.issueKey.isNotBlank() && it.recoveredAtMs == null
    }
    val recoveredCount = records.count {
        isRecoverableLevel(it.level) && it.issueKey.isNotBlank() && it.recoveredAtMs != null
    }
    val errorCount = records.count { it.level == "ERROR" || it.level == "FATAL" }
    val crashCount = records.count {
        it.level == "FATAL" ||
            it.tag.contains("Crash", ignoreCase = true) ||
            it.tag == "PreviousCrash" ||
            (it.tag == "ProcessExitInfo" && (it.level == "ERROR" || it.level == "FATAL"))
    }
    val warningCount = records.count { it.level == "WARN" }

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
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF202124))
            }
            Text(
                "应用日志",
                modifier = Modifier.weight(1f),
                color = Color(0xFF202124),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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
                        text = { Text(if (exporting) "正在导出…" else "导出 AI 诊断报告") },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                        enabled = !exporting,
                        onClick = {
                            showActionsMenu = false
                            incidentExportQuery = ""
                            if (records.none { it.level == "ERROR" || it.level == "FATAL" }) {
                                message = "暂无可导出的错误记录"
                            } else {
                                showIncidentExportPicker = true
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (exporting) "正在整理…" else "复制 AI 修复报告") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        enabled = !exporting,
                        onClick = {
                            showActionsMenu = false
                            scope.launch {
                                exporting = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { AppLogStore.exportCompactText(ctx.applicationContext) }
                                }
                                result.onSuccess { report ->
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText("AI 修复诊断报告", report),
                                    )
                                    message = "AI 修复报告已复制"
                                }.onFailure { failure ->
                                    message = "报告生成失败：${failure.message ?: "未知错误"}"
                                }
                                exporting = false
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
            placeholder = { Text("搜索错误、模块或关键词", maxLines = 1) },
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

        message?.let { text ->
            Text(
                text,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                color = if (text.startsWith("导出失败") || text.startsWith("清理失败")) {
                    Color(0xFFB42318)
                } else {
                    Color(0xFF357A52)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (visibleRecords.isEmpty()) {
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
                    items = visibleRecords,
                    key = { "${it.timestampMs}-${it.tag}-${it.message.hashCode()}" },
                ) { record ->
                    LogRecordCard(record = record, onClick = { selectedRecord = record })
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
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "选择要导出的错误",
                                color = Color(0xFF202124),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "选择一条错误记录，生成完整 AI 诊断报告",
                                color = Color(0xFF737980),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "错误记录",
                            color = Color(0xFF202124),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "FATAL 优先 · 同级最新在前",
                            color = Color(0xFF858B93),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
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
                                } ?: "尚未选择错误",
                                color = Color(0xFF202124),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                selectedIncidentForExport?.let { selected ->
                                    "${selected.tag} · ${AppLogStore.formatTime(selected.timestampMs)}"
                                } ?: "报告将保留完整堆栈和可确认关联的上下文",
                                color = Color(0xFF858B93),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        AppLogStore.formatTime(record.timestampMs),
                        color = Color(0xFF737980),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        AppLogTitleResolver.title(record.tag, record.message),
                        color = Color(0xFF202124),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val diagnosis = remember(record.timestampMs, record.tag, record.message, record.details) {
                        AppLogDiagnostics.analyze(record)
                    }
                    val coverage = remember(record.eventId, record.tag, record.message, record.diagnosticContext) {
                        AppLogDiagnosticCoveragePolicy.analyze(record)
                    }
                    if (isRecoverableLevel(record.level)) {
                        DiagnosisSummaryCard(diagnosis)
                        DiagnosticCoverageCard(coverage)
                    }

                    // Put request/response facts up front; users should not have to scan a long
                    // stack trace to find the HTTP code, response type, or HLS parsing stage.
                    val diagnosticContextLines = record.details
                        .substringAfter("故障上下文（用于定位发生时的状态）：\n", "")
                        .lineSequence()
                        .takeWhile { it.startsWith("- ") }
                        .toList()
                    if (diagnosticContextLines.isNotEmpty()) {
                        DetailSection(
                            title = "检测上下文（请求 / HTTP / 清单解析）",
                            text = diagnosticContextLines.joinToString("\n"),
                        )
                    }

                    if (isRecoverableLevel(record.level) && record.issueKey.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (record.recoveredAtMs != null) Color(0xFFEAF7EF) else Color(0xFFFFF1F0),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    if (record.recoveredAtMs != null) "已恢复" else "尚无恢复成功记录",
                                    color = if (record.recoveredAtMs != null) Color(0xFF216E39) else Color(0xFFB42318),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                record.recoveredAtMs?.let {
                                    Text(
                                        "恢复时间：${AppLogStore.formatTime(it)}",
                                        color = Color(0xFF565C63),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text(
                                    "故障编号：${record.issueKey}",
                                    color = Color(0xFF565C63),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    if (record.thread.isNotBlank()) {
                        DetailSection(title = "发生线程", text = record.thread)
                    }

                    val causeChain = record.details.lineSequence()
                        .map { it.trim() }
                        .filter { it.matches(Regex("#\\d+ .+")) }
                        .take(12)
                        .toList()
                    if (causeChain.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "异常原因",
                                color = Color(0xFF202124),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFFFF8EB),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    causeChain.forEach { cause ->
                                        Text(
                                            cause,
                                            color = Color(0xFF59380B),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (record.details.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "完整日志",
                                color = Color(0xFF202124),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFF7F8FA),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    record.details,
                                    modifier = Modifier.padding(14.dp),
                                    color = Color(0xFF3F4650),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
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
                        enabled = !exporting,
                        onClick = {
                            scope.launch {
                                exporting = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        AppLogStore.exportIncidentText(ctx.applicationContext, record)
                                    }
                                }
                                result.onSuccess { report ->
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText("AI 单条故障修复包", report),
                                    )
                                    message = "AI 修复包已复制"
                                    selectedRecord = null
                                }.onFailure { failure ->
                                    message = "AI 修复包生成失败：${failure.message ?: "未知错误"}"
                                }
                                exporting = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandBright,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (exporting) "正在整理…" else "复制 AI 修复包")
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

private fun incidentReportFileName(record: AppLogRecord): String {
    val module = record.tag
        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
        .trim('_')
        .take(36)
        .ifBlank { "Error" }
    val time = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        .format(Date(record.timestampMs))
    val level = record.level.filter { it.isLetterOrDigit() }.take(10).ifBlank { "ERROR" }
    val identity = record.eventId.ifBlank { record.issueKey.ifBlank { "${record.timestampMs}_${record.tag}" } }
        .filter { it.isLetterOrDigit() }
        .takeLast(8)
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
                val errorType = record.errorClass.ifBlank {
                    record.exceptionSnapshot?.type.orEmpty()
                }
                if (errorType.isNotBlank()) {
                    Text(
                        "异常：$errorType",
                        color = Color(0xFF68707A),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (record.rootCauseMessage.isNotBlank()) {
                    Text(
                        record.rootCauseMessage,
                        color = Color(0xFF737980),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val identifier = record.issueKey.ifBlank { record.eventId }
                if (identifier.isNotBlank()) {
                    Text(
                        "编号：$identifier",
                        color = Color(0xFF858B93),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

@Composable
private fun LogRecordCard(record: AppLogRecord, onClick: () -> Unit) {
    val color = levelColor(record.level)
    val diagnosis = remember(record.details, record.tag, record.message) {
        AppLogDiagnostics.analyze(record)
    }
    val rootCause = diagnosis.finding
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

@Composable
private fun DiagnosisSummaryCard(diagnosis: io.translatetube.local.util.AppLogDiagnosis) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF5F8FC),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE1E8F0)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "诊断判断",
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    diagnosis.confidence,
                    color = if (diagnosis.confidence.contains("不足") || diagnosis.confidence.contains("待验证")) {
                        Color(0xFF946200)
                    } else {
                        Color(0xFF356A4A)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                diagnosis.category,
                color = Color(0xFF26384A),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                diagnosis.finding,
                color = Color(0xFF354052),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "日志证据",
                color = Color(0xFF202124),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                diagnosis.evidence,
                color = Color(0xFF4D5968),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "建议排查",
                color = Color(0xFF202124),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            diagnosis.nextSteps.forEachIndexed { index, step ->
                Text(
                    (index + 1).toString() + ". " + step,
                    color = Color(0xFF4D5968),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticCoverageCard(coverage: AppLogDiagnosticCoverage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF7F8FA),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E8EC)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "AI 诊断证据 · " + coverage.domain,
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    coverage.completenessPercent.toString() + "%",
                    color = if (coverage.missingEvidence.isEmpty()) Color(0xFF247347) else Color(0xFF946200),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "已采集 " + coverage.collectedEvidenceCount + "/" + coverage.requiredEvidenceCount + " 项关键证据",
                color = Color(0xFF68707A),
                style = MaterialTheme.typography.bodySmall,
            )
            if (coverage.missingEvidence.isNotEmpty()) {
                Text("缺失证据", color = Color(0xFF202124), style = MaterialTheme.typography.labelLarge)
                coverage.missingEvidence.forEach {
                    Text("· " + it, color = Color(0xFF4D5968), style = MaterialTheme.typography.bodySmall)
                }
                Text("建议补采", color = Color(0xFF202124), style = MaterialTheme.typography.labelLarge)
                coverage.collectionAdvice.forEachIndexed { index, advice ->
                    Text(
                        (index + 1).toString() + ". " + advice,
                        color = Color(0xFF4D5968),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(
                    "当前模块关键证据采集项齐全；仍需结合代码和事件关系验证真实根因。",
                    color = Color(0xFF247347),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            color = Color(0xFF202124),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text,
            color = Color(0xFF565C63),
            style = MaterialTheme.typography.bodyMedium,
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
