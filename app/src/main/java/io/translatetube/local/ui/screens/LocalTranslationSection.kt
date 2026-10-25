package io.translatetube.local.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
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
import io.translatetube.local.translate.LocalTranslationEnginePolicy
import io.translatetube.local.translate.OpusMtModelManager
import io.translatetube.local.translate.OpusMtStatus
import io.translatetube.local.translate.Small100LanguagePolicy
import io.translatetube.local.translate.Small100ModelManager
import io.translatetube.local.translate.Small100Status
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
    val sourceLanguagePrefs = remember(context) { context.getSharedPreferences("settings", 0) }
    var sourceLanguageSelection by remember {
        mutableStateOf(sourceLanguagePrefs.getString("translation_source_language", "auto").orEmpty().ifBlank { "auto" })
    }
    var sourceLanguageMenuExpanded by remember { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf("") }
    var mlkitStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mlkitBusy by remember { mutableStateOf<String?>(null) }
    var mlkitChineseReady by remember { mutableStateOf(false) }
    var mlkitProgressLabel by remember { mutableStateOf("") }
    var mlkitError by remember { mutableStateOf("") }
    var opusJob by remember { mutableStateOf<Job?>(null) }
    val opusState by OpusMtModelManager.state.collectAsState()
    var small100Job by remember { mutableStateOf<Job?>(null) }
    val small100State by Small100ModelManager.state.collectAsState()
    val pairs = remember {
        listOf(
            LocalTranslationPair("南非荷兰语 → 中文", "af"),
            LocalTranslationPair("阿拉伯语 → 中文", "ar"),
            LocalTranslationPair("白俄罗斯语 → 中文", "be"),
            LocalTranslationPair("保加利亚语 → 中文", "bg"),
            LocalTranslationPair("孟加拉语 → 中文", "bn"),
            LocalTranslationPair("加泰罗尼亚语 → 中文", "ca"),
            LocalTranslationPair("捷克语 → 中文", "cs"),
            LocalTranslationPair("威尔士语 → 中文", "cy"),
            LocalTranslationPair("丹麦语 → 中文", "da"),
            LocalTranslationPair("德语 → 中文", "de"),
            LocalTranslationPair("希腊语 → 中文", "el"),
            LocalTranslationPair("英语 → 中文", "en"),
            LocalTranslationPair("世界语 → 中文", "eo"),
            LocalTranslationPair("西班牙语 → 中文", "es"),
            LocalTranslationPair("爱沙尼亚语 → 中文", "et"),
            LocalTranslationPair("波斯语 → 中文", "fa"),
            LocalTranslationPair("芬兰语 → 中文", "fi"),
            LocalTranslationPair("法语 → 中文", "fr"),
            LocalTranslationPair("爱尔兰语 → 中文", "ga"),
            LocalTranslationPair("加利西亚语 → 中文", "gl"),
            LocalTranslationPair("古吉拉特语 → 中文", "gu"),
            LocalTranslationPair("希伯来语 → 中文", "he"),
            LocalTranslationPair("印地语 → 中文", "hi"),
            LocalTranslationPair("克罗地亚语 → 中文", "hr"),
            LocalTranslationPair("海地克里奥尔语 → 中文", "ht"),
            LocalTranslationPair("匈牙利语 → 中文", "hu"),
            LocalTranslationPair("印尼语 → 中文", "id"),
            LocalTranslationPair("冰岛语 → 中文", "is"),
            LocalTranslationPair("意大利语 → 中文", "it"),
            LocalTranslationPair("日语 → 中文", "ja"),
            LocalTranslationPair("格鲁吉亚语 → 中文", "ka"),
            LocalTranslationPair("卡纳达语 → 中文", "kn"),
            LocalTranslationPair("韩语 → 中文", "ko"),
            LocalTranslationPair("立陶宛语 → 中文", "lt"),
            LocalTranslationPair("拉脱维亚语 → 中文", "lv"),
            LocalTranslationPair("马其顿语 → 中文", "mk"),
            LocalTranslationPair("马拉地语 → 中文", "mr"),
            LocalTranslationPair("马来语 → 中文", "ms"),
            LocalTranslationPair("马耳他语 → 中文", "mt"),
            LocalTranslationPair("荷兰语 → 中文", "nl"),
            LocalTranslationPair("挪威语 → 中文", "no"),
            LocalTranslationPair("波兰语 → 中文", "pl"),
            LocalTranslationPair("葡萄牙语 → 中文", "pt"),
            LocalTranslationPair("罗马尼亚语 → 中文", "ro"),
            LocalTranslationPair("俄语 → 中文", "ru"),
            LocalTranslationPair("斯洛伐克语 → 中文", "sk"),
            LocalTranslationPair("斯洛文尼亚语 → 中文", "sl"),
            LocalTranslationPair("阿尔巴尼亚语 → 中文", "sq"),
            LocalTranslationPair("瑞典语 → 中文", "sv"),
            LocalTranslationPair("斯瓦希里语 → 中文", "sw"),
            LocalTranslationPair("泰米尔语 → 中文", "ta"),
            LocalTranslationPair("泰卢固语 → 中文", "te"),
            LocalTranslationPair("泰语 → 中文", "th"),
            LocalTranslationPair("他加禄语 → 中文", "tl"),
            LocalTranslationPair("土耳其语 → 中文", "tr"),
            LocalTranslationPair("乌克兰语 → 中文", "uk"),
            LocalTranslationPair("乌尔都语 → 中文", "ur"),
            LocalTranslationPair("越南语 → 中文", "vi"),
        )
    }

    val smallPairs = remember {
        Small100LanguagePolicy.languages.map { (code, name) -> LocalTranslationPair(name + " → 中文", code) }
    }
    val allSourcePairs = remember { (pairs + smallPairs).distinctBy { it.source } }
    val visibleSourcePairs = if (activeId == EngineFactory.ID_LOCAL_SMALL100 || detail == "small100") smallPairs else pairs

    suspend fun refreshMlKit() {
        val snapshot = withContext(Dispatchers.IO) {
            val chineseReady = MlKitModelCoordinator.isDownloaded("zh")
            val statuses = pairs.associate { pair ->
                pair.source to if (
                    MlKitModelCoordinator.isDownloaded(pair.source) && chineseReady
                ) "ready" else "missing"
            }
            chineseReady to statuses
        }
        mlkitChineseReady = snapshot.first
        mlkitStatuses = snapshot.second
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
        if (detail == "small100" || detail.isBlank()) {
            try {
                Small100ModelManager.refresh(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogStore.error(
                    context,
                    "LocalTranslationModel",
                    "读取 SMaLL-100 模型状态失败",
                    error,
                    diagnosticContext = mapOf("操作" to "读取模型文件和验证状态"),
                )
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (detail.isBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("源语言", color = Color(0xFF172033), style = MaterialTheme.typography.titleMedium)
                Box {
                    OutlinedButton(onClick = { sourceLanguageMenuExpanded = true }) {
                        Text(
                            if (sourceLanguageSelection == "auto") "自动识别"
                            else allSourcePairs.firstOrNull { it.source == sourceLanguageSelection }?.name?.substringBefore(" →")
                                ?: "自动识别",
                        )
                        Icon(Icons.Filled.ExpandMore, contentDescription = "选择源语言")
                    }
                    DropdownMenu(
                        expanded = sourceLanguageMenuExpanded,
                        onDismissRequest = { sourceLanguageMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("自动识别") },
                            onClick = {
                                sourceLanguageSelection = "auto"
                                sourceLanguagePrefs.edit().putString("translation_source_language", "auto").apply()
                                sourceLanguageMenuExpanded = false
                            },
                        )
                        visibleSourcePairs.forEach { pair ->
                            DropdownMenuItem(
                                text = { Text(pair.name.substringBefore(" →")) },
                                onClick = {
                                    sourceLanguageSelection = pair.source
                                    sourceLanguagePrefs.edit().putString("translation_source_language", pair.source).apply()
                                    sourceLanguageMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "默认自动识别；识别不准可手动指定。目标固定为中文。ML Kit 只使用已下载的语言包，不会自动切换在线翻译。",
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.bodySmall,
                )
                val manualMlKitReady = sourceLanguageSelection != "auto" &&
                    mlkitStatuses[sourceLanguageSelection] == "ready"
                if (activeId == EngineFactory.ID_LOCAL_OPUS &&
                    sourceLanguageSelection != "auto" &&
                    !LocalTranslationEnginePolicy.supportsOpusSource(sourceLanguageSelection)
                ) {
                    Text(
                        "当前源语言与 OPUS-MT 不兼容。OPUS-MT 只支持英语 → 简体中文，请切换 ML Kit 或把源语言改为英语。",
                        color = Color(0xFFB42318),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (activeId == EngineFactory.ID_LOCAL_MLKIT &&
                    sourceLanguageSelection != "auto" &&
                    mlkitStatuses.isNotEmpty() &&
                    !manualMlKitReady
                ) {
                    Text(
                        "尚未下载所选源语言的离线模型，请先下载对应语言包。",
                        color = Color(0xFFB45309),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val manualMlKitReady = sourceLanguageSelection != "auto" &&
                mlkitStatuses[sourceLanguageSelection] == "ready"
            val mlKitHasReadyLanguage = mlkitStatuses.values.any { it == "ready" }
            val mlKitReady = (if (sourceLanguageSelection == "auto") mlKitHasReadyLanguage else manualMlKitReady) &&
                mlkitBusy == null
            val opusSourceCompatible = sourceLanguageSelection == "auto" ||
                LocalTranslationEnginePolicy.supportsOpusSource(sourceLanguageSelection)
            val opusReady = opusState.status == OpusMtStatus.READY &&
                opusJob?.isActive != true &&
                opusSourceCompatible
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
                actionLabel = when {
                    !opusSourceCompatible -> "仅英语"
                    opusReady -> "设为当前"
                    else -> "下载"
                },
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
                actionEnabled = opusSourceCompatible,
                disabledActionLabel = "仅英语",
            )
            val small100Compatible = sourceLanguageSelection == "auto" ||
                Small100LanguagePolicy.supportsSource(sourceLanguageSelection)
            val small100Ready = small100State.status == Small100Status.READY &&
                small100Job?.isActive != true &&
                small100Compatible
            LocalEngineCard(
                logo = R.drawable.small100_logo,
                name = "SMaLL-100",
                selected = activeId == EngineFactory.ID_LOCAL_SMALL100,
                actionLabel = when {
                    !small100Compatible -> "选择语言"
                    small100Ready -> "设为当前"
                    else -> "下载"
                },
                onClick = { detail = "small100" },
                onAction = {
                    if (small100Ready) onSelectEngine(EngineFactory.ID_LOCAL_SMALL100)
                    else detail = "small100"
                },
                actionEnabled = small100Compatible,
                disabledActionLabel = "选择语言",
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
                    when (detail) { "mlkit" -> "ML Kit"; "opus" -> "OPUS-MT"; else -> "SMaLL-100" },
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
                    Text(
                        "每个语言包约 30 MB。中文模型只需下载一次，后续语言共用；下载时会显示当前正在下载的语言包和阶段。ML Kit 暂不提供实时字节数或百分比。",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                                Text(
                                    "本语言包约 30 MB · 中文包 " +
                                        if (mlkitChineseReady) "已下载（共用）" else "首次另需约 30 MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF64748B),
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
                                        mlkitProgressLabel = "正在检查已下载语言包…"
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
                                                val missingLanguages = withContext(Dispatchers.IO) {
                                                    listOf(pair.source, pair.target).distinct()
                                                        .filterNot { MlKitModelCoordinator.isDownloaded(it) }
                                                }
                                                if (missingLanguages.isEmpty()) {
                                                    mlkitProgressLabel = "模型已存在，正在复核安装状态…"
                                                } else {
                                                    missingLanguages.forEachIndexed { stageIndex, languageTag ->
                                                        val languageName = if (languageTag == pair.source) {
                                                            pair.name.substringBefore(" →")
                                                        } else {
                                                            "中文"
                                                        }
                                                        mlkitProgressLabel =
                                                            "正在下载${languageName}模型包（第 ${stageIndex + 1}/${missingLanguages.size} 个）"
                                                        withContext(Dispatchers.IO) {
                                                            MlKitModelCoordinator.downloadLanguage(languageTag)
                                                        }
                                                    }
                                                }
                                                withContext(Dispatchers.IO) {
                                                    if (!MlKitModelCoordinator.isDownloaded(pair.source) ||
                                                        !MlKitModelCoordinator.isDownloaded(pair.target)
                                                    ) {
                                                        throw IllegalStateException("下载后未检测到完整语言模型")
                                                    }
                                                }
                                                mlkitProgressLabel = "语言包下载完成，正在刷新状态…"
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
                                                mlkitProgressLabel = ""
                                            }
                                        }
                                    },
                                ) { Text(if (state == "failed") "重试" else "下载") }
                            }
                        }
                        if (mlkitBusy == pair.source) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF3569D4),
                                )
                                Text(
                                    mlkitProgressLabel.ifBlank { "正在通过 ML Kit 下载语言模型…" },
                                    color = Color(0xFF3569D4),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (index < pairs.lastIndex) HorizontalDivider(color = Color(0xFFE8ECF3))
                    }
                    if (mlkitError.isNotBlank()) {
                        Text(mlkitError, color = Color(0xFFB42318), style = MaterialTheme.typography.bodySmall)
                    }
                    val selectedMlKitPairReady = if (sourceLanguageSelection == "auto") {
                        pairs.any { mlkitStatuses[it.source] == "ready" }
                    } else {
                        mlkitStatuses[sourceLanguageSelection] == "ready"
                    }
                    Button(
                        enabled = selectedMlKitPairReady && mlkitBusy == null,
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
            } else if (detail == "opus") {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("英语 → 简体中文", color = Color(0xFF172033), style = MaterialTheme.typography.titleMedium)
                    if (!opusSourceCompatible) {
                        val selectedLanguageName = pairs.firstOrNull { it.source == sourceLanguageSelection }
                            ?.name?.substringBefore(" →") ?: sourceLanguageSelection
                        Text(
                            "当前源语言为 $selectedLanguageName，OPUS-MT 不支持该语言。返回后将源语言改为英语，或改用 ML Kit。",
                            color = Color(0xFFB42318),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "仅支持英语字幕；其他语言请使用 ML Kit 对应语言包。",
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                            enabled = opusSourceCompatible,
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
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("源语言（100 种）", color = Color(0xFF172033), style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton(onClick = { sourceLanguageMenuExpanded = true }) {
                            Text(
                                if (sourceLanguageSelection == "auto") "自动识别"
                                else Small100LanguagePolicy.display(sourceLanguageSelection),
                            )
                            Icon(Icons.Filled.ExpandMore, contentDescription = "选择 SMaLL-100 源语言")
                        }
                        DropdownMenu(
                            expanded = sourceLanguageMenuExpanded,
                            onDismissRequest = { sourceLanguageMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("自动识别") },
                                onClick = {
                                    sourceLanguageSelection = "auto"
                                    sourceLanguagePrefs.edit().putString("translation_source_language", "auto").apply()
                                    sourceLanguageMenuExpanded = false
                                },
                            )
                            smallPairs.forEach { pair ->
                                DropdownMenuItem(
                                    text = { Text(pair.name.substringBefore(" →")) },
                                    onClick = {
                                        sourceLanguageSelection = pair.source
                                        sourceLanguagePrefs.edit().putString("translation_source_language", pair.source).apply()
                                        sourceLanguageMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        "100 种语言 → 简体中文。模型总下载量约 615 MB；下载中断后可续传。首次加载建议至少有 1 GB 可用运行内存。全部文件通过校验并完成实际试译后才会启用。",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when (small100State.status) {
                        Small100Status.READY -> Text("模型已下载并通过试译验证", color = Color(0xFF25804A), style = MaterialTheme.typography.labelMedium)
                        Small100Status.DOWNLOADING -> Text(
                            "正在下载 " + small100State.currentFile,
                            color = Color(0xFF3569D4),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Small100Status.VERIFYING -> Text("正在加载模型并验证中文翻译", color = Color(0xFF3569D4), style = MaterialTheme.typography.labelMedium)
                        Small100Status.PAUSED -> Text(
                            if (small100State.error.isNotBlank()) small100State.error + " · 可继续" else "下载已暂停，可继续",
                            color = if (small100State.error.isNotBlank()) Color(0xFFB42318) else Color(0xFF64748B),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Small100Status.FAILED -> Text(
                            small100State.error.ifBlank { "下载或验证失败，可重试" },
                            color = Color(0xFFB42318),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> Text("约 615 MB", color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
                    }
                    if (small100State.status == Small100Status.DOWNLOADING ||
                        small100State.status == Small100Status.VERIFYING
                    ) {
                        LinearProgressIndicator(
                            progress = (small100State.progress / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF3569D4),
                        )
                        Text(
                            small100State.progress.toString() + "% · " +
                                formatLocalModelBytes(small100State.downloadedBytes) + " / " +
                                formatLocalModelBytes(small100State.totalBytes) + " · " +
                                small100State.fileIndex + "/" + small100State.fileCount,
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    val smallSourceCompatible = sourceLanguageSelection == "auto" ||
                        Small100LanguagePolicy.supportsSource(sourceLanguageSelection)
                    if (small100Job?.isActive == true ||
                        small100State.status == Small100Status.DOWNLOADING ||
                        small100State.status == Small100Status.VERIFYING
                    ) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                small100Job?.cancel()
                                small100Job = null
                            },
                        ) { Text(if (small100State.status == Small100Status.VERIFYING) "取消验证" else "暂停下载") }
                    } else if (small100State.status != Small100Status.READY) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                small100Job = scope.launch(Dispatchers.IO) {
                                    try {
                                        Small100ModelManager.download(context)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // Detailed failure is retained in the model state and app log.
                                    }
                                }
                            },
                        ) {
                            Text(
                                if (small100State.status == Small100Status.PAUSED ||
                                    Small100ModelManager.hasPartialDownload(context)
                                ) "继续下载" else "下载模型"
                            )
                        }
                    } else {
                        if (activeId != EngineFactory.ID_LOCAL_SMALL100) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { Small100ModelManager.delete(context) }
                                        } catch (error: Exception) {
                                            AppLogStore.error(context, "LocalTranslationModel", "删除 SMaLL-100 模型失败", error)
                                        }
                                    }
                                },
                            ) { Text("删除模型") }
                        }
                        Button(
                            enabled = smallSourceCompatible,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onSelectEngine(EngineFactory.ID_LOCAL_SMALL100)
                                AppLogStore.info(
                                    context,
                                    "LocalTranslationModel",
                                    "SMaLL-100 已设为当前翻译引擎",
                                    diagnosticContext = mapOf(
                                        "支持语言数" to Small100LanguagePolicy.languages.size.toString(),
                                        "模型" to "SMaLL-100 ONNX int8",
                                    ),
                                )
                            },
                        ) {
                            Text(if (activeId == EngineFactory.ID_LOCAL_SMALL100) "正在使用 SMaLL-100" else "设为当前翻译引擎")
                        }
                    }
                    Text(
                        "casawolice/small100-onnx · MIT",
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
    actionEnabled: Boolean = true,
    disabledActionLabel: String? = null,
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
        } else if (!actionEnabled) {
            Text(
                disabledActionLabel ?: actionLabel,
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.labelMedium,
            )
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
