package io.translatetube.local.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.translatetube.local.R
import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.translate.MlKitModelCoordinator
import io.translatetube.local.translate.OpusMtModelManager
import io.translatetube.local.translate.OpusMtStatus
import io.translatetube.local.util.AppLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class LocalTranslationPair(val name: String, val source: String, val target: String = "zh")

@Composable
internal fun LocalTranslationSection(
    modifier: Modifier = Modifier,
    activeId: Long,
    onSelectEngine: (Long) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var detail by rememberSaveable { mutableStateOf("") }
    var mlkitStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mlkitBusy by remember { mutableStateOf<String?>(null) }
    var mlkitError by remember { mutableStateOf("") }
    var opusJob by remember { mutableStateOf<Job?>(null) }
    val opusState by OpusMtModelManager.state.collectAsState()
    val pairs = remember {
        listOf(
            LocalTranslationPair("英语 → 中文", "en"),
            LocalTranslationPair("日语 → 中文", "ja"),
            LocalTranslationPair("韩语 → 中文", "ko"),
            LocalTranslationPair("法语 → 中文", "fr"),
            LocalTranslationPair("德语 → 中文", "de"),
            LocalTranslationPair("西班牙语 → 中文", "es"),
            LocalTranslationPair("葡萄牙语 → 中文", "pt"),
            LocalTranslationPair("俄语 → 中文", "ru"),
            LocalTranslationPair("意大利语 → 中文", "it"),
            LocalTranslationPair("阿拉伯语 → 中文", "ar"),
            LocalTranslationPair("印地语 → 中文", "hi"),
            LocalTranslationPair("泰语 → 中文", "th"),
            LocalTranslationPair("越南语 → 中文", "vi"),
            LocalTranslationPair("印尼语 → 中文", "id"),
            LocalTranslationPair("荷兰语 → 中文", "nl"),
            LocalTranslationPair("波兰语 → 中文", "pl"),
            LocalTranslationPair("土耳其语 → 中文", "tr"),
            LocalTranslationPair("瑞典语 → 中文", "sv"),
            LocalTranslationPair("丹麦语 → 中文", "da"),
            LocalTranslationPair("芬兰语 → 中文", "fi"),
            LocalTranslationPair("挪威语 → 中文", "no"),
            LocalTranslationPair("希腊语 → 中文", "el"),
            LocalTranslationPair("希伯来语 → 中文", "he"),
            LocalTranslationPair("匈牙利语 → 中文", "hu"),
            LocalTranslationPair("捷克语 → 中文", "cs"),
            LocalTranslationPair("罗马尼亚语 → 中文", "ro"),
            LocalTranslationPair("乌克兰语 → 中文", "uk"),
            LocalTranslationPair("孟加拉语 → 中文", "bn"),
            LocalTranslationPair("马来语 → 中文", "ms"),
        )
    }

    suspend fun refreshMlKit() {
        mlkitStatuses = withContext(Dispatchers.IO) {
            pairs.associate { pair ->
                pair.source to if (
                    MlKitModelCoordinator.isDownloaded(pair.source) &&
                    MlKitModelCoordinator.isDownloaded(pair.target)
                ) "ready" else "missing"
            }
        }
    }

    LaunchedEffect(detail) {
        if (detail == "mlkit" || detail.isBlank()) {
            try {
                refreshMlKit()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mlkitError = error.message ?: "无法读取语言模型状态"
                AppLogStore.error(
                    context,
                    "LocalTranslationModel",
                    "读取 ML Kit 语言模型状态失败",
                    error,
                    diagnosticContext = mapOf("操作" to "查询已下载模型"),
                )
            }
        }
        if (detail == "opus" || detail.isBlank()) {
            try {
                OpusMtModelManager.refresh(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogStore.error(
                    context,
                    "LocalTranslationModel",
                    "读取 OPUS-MT 模型状态失败",
                    error,
                    diagnosticContext = mapOf("操作" to "读取模型状态"),
                )
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (detail.isBlank()) {
            val mlKitReady = mlkitStatuses.values.any { it == "ready" } && mlkitBusy == null
            val opusReady = opusState.status == OpusMtStatus.READY && opusJob?.isActive != true
            LocalEngineCard(
                logo = R.drawable.mlkit_logo,
                name = "ML Kit",
                selected = activeId == EngineFactory.ID_LOCAL_MLKIT,
                actionLabel = if (mlKitReady) "设为当前" else "下载",
                onClick = { detail = "mlkit" },
                onAction = {
                    if (mlKitReady) {
                        onSelectEngine(EngineFactory.ID_LOCAL_MLKIT)
                        AppLogStore.info(
                            context, "LocalTranslationModel", "ML Kit 已从本地翻译首页设为当前引擎",
                            diagnosticContext = mapOf("已下载语言对数" to mlkitStatuses.values.count { it == "ready" }.toString()),
                        )
                    } else detail = "mlkit"
                },
            )
            LocalEngineCard(
                logo = R.drawable.opus_mt_logo,
                name = "OPUS-MT",
                selected = activeId == EngineFactory.ID_LOCAL_OPUS,
                actionLabel = if (opusReady) "设为当前" else "下载",
                onClick = { detail = "opus" },
                onAction = {
                    if (opusReady) {
                        onSelectEngine(EngineFactory.ID_LOCAL_OPUS)
                        AppLogStore.info(
                            context, "LocalTranslationModel", "OPUS-MT 已从本地翻译首页设为当前引擎",
                            diagnosticContext = mapOf("模型" to "opus-mt-en-zh"),
                        )
                    } else detail = "opus"
                },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { detail = ""; mlkitError = "" },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text("‹ 返回") }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (detail == "mlkit") "ML Kit" else "OPUS-MT",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF172033),
                )
            }
            if (detail == "mlkit") {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pairs.forEachIndexed { index, pair ->
                        val state = mlkitStatuses[pair.source] ?: "checking"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    pair.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFF172033),
                                )
                                when (state) {
                                    "ready" -> Text("已下载", style = MaterialTheme.typography.labelSmall, color = Color(0xFF25804A))
                                    "downloading" -> Text("正在下载", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3569D4))
                                    "failed" -> Text("下载失败", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB42318))
                                    "checking" -> Text("检查中…", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                                }
                            }
                            when (state) {
                                "downloading", "checking" -> CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF3569D4),
                                )
                                "ready" -> TextButton(
                                    enabled = mlkitBusy == null && activeId != EngineFactory.ID_LOCAL_MLKIT,
                                    onClick = {
                                        mlkitBusy = pair.source
                                        mlkitError = ""
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    MlKitModelCoordinator.deleteLanguage(pair.source)
                                                    val otherSourcesReady = pairs.any { other ->
                                                        other.source != pair.source &&
                                                            MlKitModelCoordinator.isDownloaded(other.source)
                                                    }
                                                    if (!otherSourcesReady) {
                                                        MlKitModelCoordinator.deleteLanguage(pair.target)
                                                    }
                                                }
                                                AppLogStore.info(
                                                    context,
                                                    "LocalTranslationModel",
                                                    "ML Kit 语言模型已删除",
                                                    diagnosticContext = mapOf("语言对" to pair.name),
                                                )
                                                refreshMlKit()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                mlkitError = error.message ?: "删除失败"
                                                AppLogStore.error(
                                                    context,
                                                    "LocalTranslationModel",
                                                    "删除 ML Kit 语言模型失败",
                                                    error,
                                                    diagnosticContext = mapOf("语言对" to pair.name),
                                                )
                                            } finally {
                                                mlkitBusy = null
                                            }
                                        }
                                    },
                                ) { Text(if (activeId == EngineFactory.ID_LOCAL_MLKIT) "使用中" else "删除") }
                                else -> TextButton(
                                    enabled = mlkitBusy == null,
                                    onClick = {
                                        mlkitBusy = pair.source
                                        mlkitStatuses = mlkitStatuses + (pair.source to "downloading")
                                        mlkitError = ""
                                        AppLogStore.info(
                                            context,
                                            "LocalTranslationModel",
                                            "开始下载 ML Kit 语言模型",
                                            diagnosticContext = mapOf(
                                                "语言对" to pair.name,
                                                "源语言" to pair.source,
                                                "目标语言" to pair.target,
                                            ),
                                        )
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    MlKitModelCoordinator.downloadLanguage(pair.source)
                                                    MlKitModelCoordinator.downloadLanguage(pair.target)
                                                    if (!MlKitModelCoordinator.isDownloaded(pair.source) ||
                                                        !MlKitModelCoordinator.isDownloaded(pair.target)
                                                    ) {
                                                        throw IllegalStateException("下载后未检测到完整语言模型")
                                                    }
                                                }
                                                AppLogStore.info(
                                                    context,
                                                    "LocalTranslationModel",
                                                    "ML Kit 语言模型下载完成",
                                                    diagnosticContext = mapOf("语言对" to pair.name),
                                                )
                                                refreshMlKit()
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                mlkitStatuses = mlkitStatuses + (pair.source to "failed")
                                                mlkitError = error.message ?: "下载失败，请重试"
                                                AppLogStore.error(
                                                    context,
                                                    "LocalTranslationModel",
                                                    "ML Kit 语言模型下载失败",
                                                    error,
                                                    diagnosticContext = mapOf(
                                                        "语言对" to pair.name,
                                                        "是否可重试" to "true",
                                                    ),
                                                )
                                            } finally {
                                                mlkitBusy = null
                                            }
                                        }
                                    },
                                ) { Text(if (state == "failed") "重试" else "下载") }
                            }
                        }
                        if (index < pairs.lastIndex) HorizontalDivider(color = Color(0xFFE8ECF3))
                    }
                    if (mlkitBusy != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF3569D4))
                    }
                    if (mlkitError.isNotBlank()) {
                        Text(mlkitError, color = Color(0xFFB42318), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = pairs.any { mlkitStatuses[it.source] == "ready" } && mlkitBusy == null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onSelectEngine(EngineFactory.ID_LOCAL_MLKIT)
                            AppLogStore.info(
                                context,
                                "LocalTranslationModel",
                                "ML Kit 已设为当前翻译引擎",
                                diagnosticContext = mapOf("已下载语言对数" to pairs.count { mlkitStatuses[it.source] == "ready" }.toString()),
                            )
                        },
                    ) {
                        Text(if (activeId == EngineFactory.ID_LOCAL_MLKIT) "正在使用 ML Kit" else "设为当前翻译引擎")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("英语 → 简体中文", color = Color(0xFF172033), style = MaterialTheme.typography.titleMedium)
                    when (opusState.status) {
                        OpusMtStatus.READY -> Text("已下载", color = Color(0xFF25804A), style = MaterialTheme.typography.labelMedium)
                        OpusMtStatus.DOWNLOADING -> Text("正在下载 ${opusState.currentFile}", color = Color(0xFF3569D4), style = MaterialTheme.typography.labelMedium)
                        OpusMtStatus.VERIFYING -> Text("正在验证模型", color = Color(0xFF3569D4), style = MaterialTheme.typography.labelMedium)
                        OpusMtStatus.PAUSED -> Text(
                            if (opusState.error.isNotBlank()) "${opusState.error} · 可继续下载" else "下载已暂停，可继续",
                            color = if (opusState.error.isNotBlank()) Color(0xFFB42318) else Color(0xFF64748B),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        OpusMtStatus.FAILED -> Text(opusState.error.ifBlank { "下载失败，可重试" }, color = Color(0xFFB42318), style = MaterialTheme.typography.bodySmall)
                        else -> Text("约 250 MB", color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
                    }
                    if (opusState.status == OpusMtStatus.VERIFYING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF3569D4))
                    } else if (opusState.status == OpusMtStatus.DOWNLOADING) {
                        if (opusState.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = (opusState.progress / 100f).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF3569D4),
                            )
                            Text(
                                "${opusState.progress}% · ${formatLocalModelBytes(opusState.downloadedBytes)} / ${formatLocalModelBytes(opusState.totalBytes)} · ${opusState.fileIndex}/${opusState.fileCount}",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF3569D4))
                            Text(
                                "${formatLocalModelBytes(opusState.downloadedBytes)} 已下载 · ${opusState.fileIndex}/${opusState.fileCount}",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (opusJob?.isActive == true || opusState.status == OpusMtStatus.DOWNLOADING) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                opusJob?.cancel()
                                opusJob = null
                            },
                        ) { Text(if (opusState.status == OpusMtStatus.VERIFYING) "取消验证" else "暂停下载") }
                    } else if (opusState.status != OpusMtStatus.READY) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                opusJob = scope.launch(Dispatchers.IO) {
                                    try {
                                        OpusMtModelManager.download(context)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // The exact failure remains in the model state and diagnostic log.
                                    }
                                }
                            },
                        ) {
                            Text(
                                if (opusState.status == OpusMtStatus.PAUSED || OpusMtModelManager.hasPartialDownload(context))
                                    "继续下载" else "下载模型"
                            )
                        }
                    } else {
                        if (activeId != EngineFactory.ID_LOCAL_OPUS) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { OpusMtModelManager.delete(context) }
                                        } catch (error: Exception) {
                                            AppLogStore.error(context, "LocalTranslationModel", "删除 OPUS-MT 模型失败", error)
                                        }
                                    }
                                },
                            ) { Text("删除模型") }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onSelectEngine(EngineFactory.ID_LOCAL_OPUS)
                                AppLogStore.info(
                                    context,
                                    "LocalTranslationModel",
                                    "OPUS-MT 已设为当前翻译引擎",
                                    diagnosticContext = mapOf("模型" to "opus-mt-en-zh"),
                                )
                            },
                        ) { Text(if (activeId == EngineFactory.ID_LOCAL_OPUS) "正在使用 OPUS-MT" else "设为当前翻译引擎") }
                    }
                    Text(
                        "Helsinki-NLP / ONNX Community · CC BY 4.0",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalEngineCard(
    logo: Int,
    name: String,
    selected: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Color(0xFF3569D4) else Color(0xFFE3E9F3),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = logo,
                contentDescription = "$name 官方项目标识",
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Color(0xFF172033))
                if (selected) {
                    Text("当前翻译引擎", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3569D4))
                }
            }
            Spacer(Modifier.width(4.dp))
            Text("管理", color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
        }
        if (selected) {
            Text("使用中", color = Color(0xFF3569D4), style = MaterialTheme.typography.labelMedium)
        } else {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                Text(actionLabel, maxLines = 1)
            }
        }
    }
}

private fun formatLocalModelBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024f * 1024f)
    return if (mb >= 100f) "${mb.toInt()} MB" else String.format(Locale.ROOT, "%.1f MB", mb)
}
