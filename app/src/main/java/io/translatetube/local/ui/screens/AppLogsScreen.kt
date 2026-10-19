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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
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
                record.thread.contains(query, ignoreCase = true)
        }
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
        Modifier.fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFFF3F6FC))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回设置", tint = OnBg)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("应用日志", color = OnBg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("本地诊断记录 · 不会自动上传", color = SubText, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新日志", tint = BrandBright)
            }
            IconButton(
                enabled = !exporting,
                onClick = { exportLauncher.launch("elephant-translate-logs.txt") },
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = "导出诊断日志", tint = BrandBright)
            }
            IconButton(onClick = { showClearConfirm = true }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "清理日志", tint = DangerRed)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                LogSummaryCard("总数", records.size.toString(), Modifier.weight(1f))
                LogSummaryCard("错误", errorCount.toString(), Modifier.weight(1f), DangerRed)
                LogSummaryCard("警告", warningCount.toString(), Modifier.weight(1f), WarnYellow)
                LogSummaryCard("闪退", crashCount.toString(), Modifier.weight(1f), DangerRed)
            }
            Text(
                "保留最近 7 天的记录。“闪退”筛选也会包含系统报告的原生崩溃、ANR 和低内存异常退出；正常关闭不会当成闪退。",
                color = SubText,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (writerHealth.isHealthy) Color(0xFFEAF7EF) else Color(0xFFFFF0F0),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (writerHealth.isHealthy) Icons.Filled.Info else Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = if (writerHealth.isHealthy) Color(0xFF18794E) else DangerRed,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (writerHealth.isHealthy) "日志写入状态正常" else "检测到日志写入失败",
                        color = if (writerHealth.isHealthy) Color(0xFF18794E) else DangerRed,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        when {
                            writerHealth.failedWriteCount == 0L ->
                                "诊断记录保存在本机，不会自动上传。"
                            writerHealth.isHealthy ->
                                "写入已恢复；本次运行累计失败 ${writerHealth.failedWriteCount} 次。最近原因：${writerHealth.lastFailureDescription}"
                            else ->
                                "当前写入仍有问题；本次运行累计失败 ${writerHealth.failedWriteCount} 次。最近原因：${writerHealth.lastFailureDescription}"
                        },
                        color = SubText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索标签、错误原因、配音引擎或线程") },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    TextButton(onClick = { searchQuery = "" }) { Text("清除") }
                }
            },
        )
        Text(
            "显示 ${visibleRecords.size} / ${records.size} 条记录",
            color = SubText,
            style = MaterialTheme.typography.labelSmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("全部", "错误", "闪退", "警告").forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SurfaceHigh,
                        selectedLabelColor = BrandBright,
                    ),
                )
            }
        }

        message?.let {
            Text(
                it,
                color = if (it.startsWith("导出失败") || it.startsWith("清理失败")) DangerRed else BrandBright,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (visibleRecords.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MutedGreen, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("暂无符合条件的日志", color = OnBg, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        records.isEmpty() -> "后续检测到的未处理异常和已接入日志的错误会显示在这里。"
                        searchQuery.isNotBlank() -> "没有找到与“$searchQuery”匹配的日志。"
                        else -> "切换筛选条件，查看其他运行记录。"
                    },
                    color = SubText,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (records.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { refreshKey++ }) { Text("重新读取") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
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
        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("日志详情", color = OnBg)
                    Text(
                        "${record.level} · ${record.tag}",
                        color = levelColor(record.level),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(AppLogStore.formatTime(record.timestampMs), color = SubText, style = MaterialTheme.typography.bodySmall)
                    Text(record.message.ifBlank { "（无错误摘要）" }, color = OnBg, style = MaterialTheme.typography.bodyMedium)
                    if (record.thread.isNotBlank()) {
                        Text("异常发生线程：${record.thread}", color = SubText, style = MaterialTheme.typography.bodySmall)
                    }
                    val causeChain = record.details.lineSequence()
                        .map { it.trim() }
                        .filter { it.matches(Regex("#\\d+ .+")) }
                        .take(12)
                        .toList()
                    if (causeChain.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFFFF7E6),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("异常根因链（从外层异常到最深层原因）", color = Color(0xFF8A4B08), style = MaterialTheme.typography.labelLarge)
                                causeChain.forEach { cause ->
                                    Text(cause, color = Color(0xFF59380B), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    if (record.details.isNotBlank()) {
                        HorizontalDivider(color = OutlineSoft)
                        Text("运行上下文、设备信息与完整堆栈", color = OnBg, style = MaterialTheme.typography.labelLarge)
                        Text(
                            record.details,
                            color = SubText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "应用日志",
                            "${AppLogStore.formatTime(record.timestampMs)} [${record.level}] ${record.tag}\n${record.message}\n${record.details}",
                        ),
                    )
                    message = "日志详情已复制"
                    selectedRecord = null
                }) { Text("复制详情") }
            },
            dismissButton = {
                TextButton(onClick = { selectedRecord = null }) { Text("关闭") }
            },
            containerColor = SurfaceDark,
        )
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
            containerColor = SurfaceDark,
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
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, OutlineSoft),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                when (record.level) {
                    "FATAL", "ERROR" -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = color)
                    "WARN" -> Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = color)
                    else -> Icon(Icons.Filled.Info, contentDescription = null, tint = color)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.message.ifBlank { record.tag }, color = OnBg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(record.level, color = color, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    AppLogStore.formatTime(record.timestampMs),
                    color = SubText,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "${record.tag} · ${record.thread.ifBlank { "未知线程" }}",
                    color = SubText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (record.details.isNotBlank()) {
                    val rootCause = record.details.lineSequence()
                        .map { it.trim() }
                        .filter { it.matches(Regex("#\\d+ .+")) }
                        .lastOrNull()
                    Text(
                        rootCause ?: record.details.lineSequence().firstOrNull { it.startsWith("- ") }?.take(160).orEmpty(),
                        color = if (record.level == "ERROR" || record.level == "FATAL") DangerRed else SubText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun levelColor(level: String): Color = when (level) {
    "FATAL" -> DangerRed
    "ERROR" -> Color(0xFFB91C1C)
    "WARN" -> WarnYellow
    else -> BrandBright
}
