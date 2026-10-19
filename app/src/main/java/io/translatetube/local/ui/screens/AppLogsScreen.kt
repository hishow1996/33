package io.translatetube.local.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun AppLogsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            exporting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = ctx.contentResolver.openOutputStream(uri, "wt")
                            ?: error("无法打开所选文件")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(AppLogStore.exportText(ctx.applicationContext)) }
                    }
                }
                message = if (result.isSuccess) "诊断日志已导出" else "导出失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                exporting = false
            }
        }
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
            record.tag.contains(query, ignoreCase = true) ||
                record.message.contains(query, ignoreCase = true) ||
                record.details.contains(query, ignoreCase = true) ||
                record.thread.contains(query, ignoreCase = true) ||
                record.issueKey.contains(query, ignoreCase = true) ||
                record.resolvesIssueKey.contains(query, ignoreCase = true)
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
                        text = { Text(if (exporting) "正在导出…" else "导出日志") },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                        enabled = !exporting,
                        onClick = {
                            showActionsMenu = false
                            exportLauncher.launch("elephant-translate-logs.txt")
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
                focusedBorderColor = Color(0xFF9CA3AF),
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
                        selectedContainerColor = Color(0xFF202124),
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                        containerColor = Color(0xFFF3F4F6),
                        labelColor = Color(0xFF454B53),
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = filter == name,
                        borderColor = Color(0xFFE7E9EC),
                        selectedBorderColor = Color(0xFF202124),
                    ),
                )
            }
        }

        if (!writerHealth.isHealthy) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                color = Color(0xFFFFF1F0),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Color(0xFFB42318))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "日志写入异常",
                            color = Color(0xFFB42318),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            writerHealth.lastFailureDescription.ifBlank { "部分日志可能未能保存。" },
                            color = Color(0xFF7A271A),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
                            "${record.level} · ${record.tag}",
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
                        record.message.ifBlank { "（无错误摘要）" },
                        color = Color(0xFF202124),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

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
                        onClick = {
                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "应用日志",
                                    "${AppLogStore.formatTime(record.timestampMs)} [${record.level}] ${record.tag}\n${record.message}\n线程：${record.thread}\n故障编号：${record.issueKey}\n${record.details}",
                                ),
                            )
                            message = "日志详情已复制"
                            selectedRecord = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF202124),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("复制详情")
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
    val rootCause = remember(record.details) {
        record.details.lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("#\\d+ .+")) }
            .lastOrNull()
            ?: record.details.lineSequence().firstOrNull { it.startsWith("- ") }?.take(180).orEmpty()
    }
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
                        record.message.ifBlank { record.tag },
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF202124),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        AppLogStore.formatTime(record.timestampMs).substringAfterLast(" "),
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
