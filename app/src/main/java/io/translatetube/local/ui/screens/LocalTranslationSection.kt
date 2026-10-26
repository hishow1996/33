package io.translatetube.local.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
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
import io.translatetube.local.translate.OpusMtAdditionalModelManager
import io.translatetube.local.translate.OpusMtStatus
import io.translatetube.local.translate.OpusMtDownloadState
import io.translatetube.local.translate.Small100LanguagePolicy
import io.translatetube.local.translate.Small100ModelManager
import io.translatetube.local.translate.Small100Status
import io.translatetube.local.translate.SinglePairModelLanguagePolicy
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
    var mlkitManualSourceLanguage by remember {
        mutableStateOf(
            sourceLanguagePrefs.getString("mlkit_manual_source_language", null)
                ?.takeIf { it.isNotBlank() && it != "auto" }
                ?: sourceLanguagePrefs.getString("translation_source_language", "en")
                    ?.takeIf { it.isNotBlank() && it != "auto" }
                ?: "en"
        )
    }
    var isMlKitManualSelection by remember {
        mutableStateOf(sourceLanguagePrefs.getString("translation_source_language", "auto").orEmpty().ifBlank { "auto" } != "auto")
    }
    val opusSupportedSources = setOf("en", "de", "fi", "sv")
    val singlePairSupportedSources = setOf("ja", "ko", "ru")
    var singlePairManualSourceLanguage by remember {
        mutableStateOf(
            sourceLanguagePrefs.getString("single_pair_manual_source_language", null)
                ?.takeIf { it in singlePairSupportedSources }
                ?: sourceLanguagePrefs.getString("translation_source_language", null)
                    ?.takeIf { it in singlePairSupportedSources }
                ?: "ja"
        )
    }
    var isSinglePairManualSelection by remember {
        mutableStateOf(
            sourceLanguagePrefs.getBoolean(
                "single_pair_manual_selection",
                sourceLanguagePrefs.getString("translation_source_language", "auto") in singlePairSupportedSources,
            )
        )
    }
    var opusManualSourceLanguage by remember {
        mutableStateOf(
            sourceLanguagePrefs.getString("opus_manual_source_language", null)
                ?.takeIf { it in opusSupportedSources }
                ?: sourceLanguagePrefs.getString("translation_source_language", null)
                    ?.takeIf { it in opusSupportedSources }
                ?: "en"
        )
    }
    var isOpusManualSelection by remember {
        mutableStateOf(
            sourceLanguagePrefs.getString("translation_source_language", "auto").orEmpty().ifBlank { "auto" } != "auto"
        )
    }
    var sourceLanguageMenuExpanded by remember { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf("") }

    var mlkitStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mlkitBusy by remember { mutableStateOf<String?>(null) }
    var mlkitError by remember { mutableStateOf("") }

    // Handle the phone back gesture inside the selected model's management page first.
    // The parent API manager has its own BackHandler for leaving to app Settings, so without
    // this nested handler a system edge-swipe skips the local-model list entirely.
    BackHandler(enabled = detail.isNotBlank()) {
        detail = ""
        mlkitError = ""
        sourceLanguageMenuExpanded = false
    }
    var opusJob by remember { mutableStateOf<Job?>(null) }
    val opusState by OpusMtModelManager.state.collectAsState()
    var opusExtraJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    val opusExtraStates by OpusMtAdditionalModelManager.states.collectAsState()
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
    suspend fun refreshMlKit() {
        mlkitStatuses = withContext(Dispatchers.IO) {
            val chineseReady = MlKitModelCoordinator.isDownloaded("zh")
            pairs.associate { pair ->
                pair.source to if (
                    MlKitModelCoordinator.isDownloaded(pair.source) && chineseReady
                ) "ready" else "missing"
            }
        }
    }

    LaunchedEffect(detail) {
        if (detail == "small100") {
            val preferredSource = sourceLanguagePrefs.getString("small100_source_language", "auto")
                ?.takeIf { it == "auto" || Small100LanguagePolicy.supportsSource(it) }
                ?: "auto"
            sourceLanguageSelection = preferredSource
            sourceLanguagePrefs.edit().putString("translation_source_language", preferredSource).apply()
        }
        if (detail == "singlePair" && sourceLanguageSelection in singlePairSupportedSources) {
            singlePairManualSourceLanguage = sourceLanguageSelection
            isSinglePairManualSelection = true
        }
        if (detail == "mlkit") {
            isMlKitManualSelection = sourceLanguageSelection != "auto"
            if (sourceLanguageSelection != "auto" && pairs.any { it.source == sourceLanguageSelection }) {
                mlkitManualSourceLanguage = sourceLanguageSelection
                sourceLanguagePrefs.edit().putString("mlkit_manual_source_language", sourceLanguageSelection).apply()
            }
        }
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
        if (detail == "opus") {
            isOpusManualSelection = sourceLanguageSelection != "auto"
            if (sourceLanguageSelection in opusSupportedSources) {
                opusManualSourceLanguage = sourceLanguageSelection
                sourceLanguagePrefs.edit()
                    .putString("opus_manual_source_language", sourceLanguageSelection)
                    .apply()
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
        if (detail == "opus") {
            try {
                OpusMtAdditionalModelManager.refresh(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogStore.error(
                    context,
                    "LocalTranslationModel",
                    "读取 OPUS-MT 多语言模型状态失败",
                    error,
                    diagnosticContext = mapOf("操作" to "读取额外 OPUS-MT 语言对模型状态"),
                )
            }
        }
        if (detail == "small100" || detail == "singlePair" || detail.isBlank()) {
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

    val opusSourceCompatible = sourceLanguageSelection == "auto" ||
        LocalTranslationEnginePolicy.supportsOpusSource(sourceLanguageSelection)

    fun setSourceLanguage(language: String) {
        sourceLanguageSelection = language
        sourceLanguagePrefs.edit().putString("translation_source_language", language).apply()
        if (detail == "small100") {
            sourceLanguagePrefs.edit().putString("small100_source_language", language).apply()
        }
        if (detail == "mlkit" && language != "auto" && pairs.any { it.source == language }) {
            mlkitManualSourceLanguage = language
            sourceLanguagePrefs.edit().putString("mlkit_manual_source_language", language).apply()
        }
        if (detail == "opus" && language in opusSupportedSources) {
            opusManualSourceLanguage = language
            sourceLanguagePrefs.edit().putString("opus_manual_source_language", language).apply()
        }
        sourceLanguageMenuExpanded = false
    }

    fun setMlKitSelectionMode(manual: Boolean) {
        isMlKitManualSelection = manual
        sourceLanguageMenuExpanded = false
        if (manual) {
            val language = mlkitManualSourceLanguage.takeIf { saved -> pairs.any { it.source == saved } } ?: "en"
            mlkitManualSourceLanguage = language
            sourceLanguageSelection = language
            sourceLanguagePrefs.edit()
                .putString("translation_source_language", language)
                .putString("mlkit_manual_source_language", language)
                .apply()
        } else {
            if (pairs.any { it.source == sourceLanguageSelection }) {
                mlkitManualSourceLanguage = sourceLanguageSelection
                sourceLanguagePrefs.edit().putString("mlkit_manual_source_language", sourceLanguageSelection).apply()
            }
            sourceLanguageSelection = "auto"
            sourceLanguagePrefs.edit().putString("translation_source_language", "auto").apply()
        }
    }

    fun selectMlKitLanguage(language: String) {
        if (!isMlKitManualSelection || pairs.none { it.source == language }) return
        sourceLanguageSelection = language
        mlkitManualSourceLanguage = language
        sourceLanguagePrefs.edit()
            .putString("translation_source_language", language)
            .putString("mlkit_manual_source_language", language)
            .apply()
    }

    fun setOpusSelectionMode(manual: Boolean) {
        isOpusManualSelection = manual
        sourceLanguageMenuExpanded = false
        if (manual) {
            val language = opusManualSourceLanguage.takeIf { it in opusSupportedSources }
                ?: sourceLanguageSelection.takeIf { it in opusSupportedSources }
                ?: "en"
            opusManualSourceLanguage = language
            sourceLanguageSelection = language
            sourceLanguagePrefs.edit()
                .putString("translation_source_language", language)
                .putString("opus_manual_source_language", language)
                .apply()
        } else {
            if (sourceLanguageSelection in opusSupportedSources) {
                opusManualSourceLanguage = sourceLanguageSelection
                sourceLanguagePrefs.edit().putString("opus_manual_source_language", sourceLanguageSelection).apply()
            }
            sourceLanguageSelection = "auto"
            sourceLanguagePrefs.edit().putString("translation_source_language", "auto").apply()
        }
    }

    fun selectOpusLanguage(language: String) {
        if (!isOpusManualSelection || language !in opusSupportedSources) return
        sourceLanguageSelection = language
        opusManualSourceLanguage = language
        sourceLanguagePrefs.edit()
            .putString("translation_source_language", language)
            .putString("opus_manual_source_language", language)
            .apply()
    }

    fun setSinglePairSelectionMode(manual: Boolean) {
        isSinglePairManualSelection = manual
        sourceLanguageMenuExpanded = false
        sourceLanguagePrefs.edit().putBoolean("single_pair_manual_selection", manual).apply()
        if (manual) {
            val language = singlePairManualSourceLanguage.takeIf { it in singlePairSupportedSources } ?: "ja"
            singlePairManualSourceLanguage = language
            sourceLanguageSelection = language
            sourceLanguagePrefs.edit()
                .putString("translation_source_language", language)
                .putString("single_pair_manual_source_language", language)
                .apply()
        } else {
            if (sourceLanguageSelection in singlePairSupportedSources) {
                singlePairManualSourceLanguage = sourceLanguageSelection
                sourceLanguagePrefs.edit().putString("single_pair_manual_source_language", sourceLanguageSelection).apply()
            }
            sourceLanguageSelection = "auto"
            sourceLanguagePrefs.edit().putString("translation_source_language", "auto").apply()
        }
    }

    fun selectSinglePairLanguage(language: String) {
        if (!isSinglePairManualSelection || language !in singlePairSupportedSources) return
        sourceLanguageSelection = language
        singlePairManualSourceLanguage = language
        sourceLanguagePrefs.edit()
            .putString("translation_source_language", language)
            .putString("single_pair_manual_source_language", language)
            .putBoolean("single_pair_manual_selection", true)
            .apply()
    }

    fun selectLocalEngine(id: Long, name: String) {
        if (id == EngineFactory.ID_LOCAL_SMALL100) {
            val preferredSource = sourceLanguagePrefs.getString("small100_source_language", "auto")
                ?.takeIf { it == "auto" || Small100LanguagePolicy.supportsSource(it) }
                ?: "auto"
            sourceLanguageSelection = preferredSource
            sourceLanguagePrefs.edit().putString("translation_source_language", preferredSource).apply()
        }
        if (id == EngineFactory.ID_LOCAL_SINGLE_PAIR) {
            isSinglePairManualSelection = sourceLanguagePrefs.getBoolean(
                "single_pair_manual_selection",
                sourceLanguageSelection in singlePairSupportedSources,
            )
            sourceLanguageSelection = if (isSinglePairManualSelection) {
                singlePairManualSourceLanguage.takeIf { it in singlePairSupportedSources } ?: "ja"
            } else "auto"
            sourceLanguagePrefs.edit().putString("translation_source_language", sourceLanguageSelection).apply()
        }
        onSelectEngine(id)
        val warning = when {
            sourceLanguageSelection != "auto" &&
                id == EngineFactory.ID_LOCAL_OPUS &&
                !LocalTranslationEnginePolicy.supportsOpusSource(sourceLanguageSelection) ->
                "OPUS-MT 目前只提供英语、德语、芬兰语、瑞典语直译中文；日语、韩语、俄语暂无已验证的应用兼容直译包，请改用其他本地引擎。"
            sourceLanguageSelection != "auto" &&
                id == EngineFactory.ID_LOCAL_SINGLE_PAIR &&
                !SinglePairModelLanguagePolicy.supportsSource(sourceLanguageSelection) ->
                "单语言对翻译模型支持日语、韩语和俄语，请在管理页选择对应语言或开启自动识别。"
            sourceLanguageSelection != "auto" &&
                id == EngineFactory.ID_LOCAL_MLKIT &&
                pairs.none { it.source == sourceLanguageSelection } ->
                "ML Kit 不支持当前手动选择的源语言。请进入管理页选择受支持的语言，或改用其他本地模型。"
            sourceLanguageSelection != "auto" &&
                id == EngineFactory.ID_LOCAL_SMALL100 &&
                !Small100LanguagePolicy.supportsSource(sourceLanguageSelection) ->
                "SMaLL-100 不支持当前手动选择的源语言。请进入管理页选择受支持的语言，或改用其他本地模型。"
            else -> null
        }
        if (!warning.isNullOrBlank()) {
            Toast.makeText(context, name + "：" + warning, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (detail.isBlank()) {
            LocalEngineCard(
                logo = R.drawable.mlkit_logo,
                name = "ML Kit",
                selected = activeId == EngineFactory.ID_LOCAL_MLKIT,
                onSelect = { selectLocalEngine(EngineFactory.ID_LOCAL_MLKIT, "ML Kit") },
                onManage = { detail = "mlkit" },
            )
            LocalEngineCard(
                logo = R.drawable.opus_mt_logo,
                name = "OPUS-MT",
                selected = activeId == EngineFactory.ID_LOCAL_OPUS,
                onSelect = { selectLocalEngine(EngineFactory.ID_LOCAL_OPUS, "OPUS-MT") },
                onManage = { detail = "opus" },
            )
            LocalEngineCard(
                logo = R.drawable.small100_logo,
                name = "SMaLL-100",
                selected = activeId == EngineFactory.ID_LOCAL_SMALL100,
                onSelect = { selectLocalEngine(EngineFactory.ID_LOCAL_SMALL100, "SMaLL-100") },
                onManage = { detail = "small100" },
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
                    when (detail) {
                        "mlkit" -> "ML Kit"
                        "opus" -> "OPUS-MT"
                        "singlePair" -> "单语言对翻译模型"
                        else -> "SMaLL-100"
                    },
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
                    MlKitSourceLanguageControl(
                        manualSelected = isMlKitManualSelection,
                        onModeSelected = { manual -> setMlKitSelectionMode(manual) },
                    )
                    pairs.forEachIndexed { index, pair ->
                        val state = mlkitStatuses[pair.source] ?: "checking"
                        val selectedLanguage = isMlKitManualSelection && mlkitManualSourceLanguage == pair.source
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedLanguage) Color(0xFFF0F5FF) else Color.Transparent)
                                .padding(start = 4.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
                        ) {
                            if (isMlKitManualSelection) {
                                RadioButton(
                                    selected = selectedLanguage,
                                    onClick = { selectMlKitLanguage(pair.source) },
                                    enabled = true,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                            Box(
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = isMlKitManualSelection) { selectMlKitLanguage(pair.source) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    pair.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selectedLanguage) Color(0xFF2858B8) else Color(0xFF172033),
                                    maxLines = 1,
                                )
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
                                                val missingLanguages = withContext(Dispatchers.IO) {
                                                    listOf(pair.source, pair.target).distinct()
                                                        .filterNot { MlKitModelCoordinator.isDownloaded(it) }
                                                }
                                                missingLanguages.forEach { languageTag ->
                                                    withContext(Dispatchers.IO) {
                                                        MlKitModelCoordinator.downloadLanguage(languageTag)
                                                    }
                                                }
                                                withContext(Dispatchers.IO) {
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
                    if (mlkitError.isNotBlank()) {
                        Text(mlkitError, color = Color(0xFFB42318), style = MaterialTheme.typography.bodySmall)
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
                    MlKitSourceLanguageControl(
                        manualSelected = isOpusManualSelection,
                        onModeSelected = { manual -> setOpusSelectionMode(manual) },
                    )
                    if (sourceLanguageSelection != "auto" && !opusSourceCompatible) {
                        Text(
                            "暂无此语言的 OPUS-MT 直译模型",
                            color = Color(0xFFB42318),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Divider(color = Color(0xFFE7EBF2))
                    listOf(
                        "英语 → 简体中文" to "en",
                        "德语 → 简体中文" to "de",
                        "芬兰语 → 简体中文" to "fi",
                        "瑞典语 → 简体中文" to "sv",
                    ).forEachIndexed { index, (language, source) ->
                        val state = if (source == "en") {
                            opusState
                        } else {
                            opusExtraStates[source] ?: OpusMtDownloadState(fileCount = 5)
                        }
                        val expectedBytes = if (source == "en") {
                            OpusMtModelManager.downloadSizeBytes()
                        } else {
                            OpusMtAdditionalModelManager.downloadSizeBytes(source)
                        }
                        val job = if (source == "en") opusJob else opusExtraJobs[source]
                        val selectedLanguage = isOpusManualSelection && sourceLanguageSelection == source
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selectedLanguage) Color(0xFFF0F5FF) else Color.Transparent)
                                    .padding(start = 4.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isOpusManualSelection) {
                                    RadioButton(
                                        selected = selectedLanguage,
                                        onClick = { selectOpusLanguage(source) },
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = isOpusManualSelection) { selectOpusLanguage(source) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        language,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (selectedLanguage) Color(0xFF2858B8) else Color(0xFF172033),
                                        maxLines = 1,
                                    )
                                    Text(
                                        formatLocalModelSizeDetail(expectedBytes),
                                        color = Color(0xFF64748B),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(
                                        when (state.status) {
                                            OpusMtStatus.READY -> "已下载并验证"
                                            OpusMtStatus.DOWNLOADING -> "下载中 ${state.progress}%"
                                            OpusMtStatus.VERIFYING -> "正在验证模型"
                                            OpusMtStatus.PAUSED -> "下载已暂停"
                                            OpusMtStatus.FAILED -> state.error.ifBlank { "下载失败" }
                                            else -> "未下载"
                                        },
                                        color = when (state.status) {
                                            OpusMtStatus.READY -> Color(0xFF25804A)
                                            OpusMtStatus.FAILED -> Color(0xFFB42318)
                                            OpusMtStatus.DOWNLOADING, OpusMtStatus.VERIFYING -> Color(0xFF3569D4)
                                            else -> Color(0xFF64748B)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                    )
                                }
                                if (state.status == OpusMtStatus.DOWNLOADING ||
                                    state.status == OpusMtStatus.VERIFYING
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (source == "en") {
                                                opusJob?.cancel()
                                                opusJob = null
                                            } else {
                                                job?.cancel()
                                                opusExtraJobs = opusExtraJobs - source
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    ) { Text("暂停", maxLines = 1) }
                                } else if (state.status == OpusMtStatus.READY) {
                                    if (activeId == EngineFactory.ID_LOCAL_OPUS &&
                                        (source == "en" || sourceLanguageSelection == source)
                                    ) {
                                        Text(
                                            "使用中",
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            color = Color(0xFF25804A),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    } else {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            if (source == "en") OpusMtModelManager.delete(context)
                                                            else OpusMtAdditionalModelManager.delete(context, source)
                                                        }
                                                    } catch (error: Exception) {
                                                        AppLogStore.error(
                                                            context,
                                                            "LocalTranslationModel",
                                                            "删除 OPUS-MT 直译模型失败",
                                                            error,
                                                            diagnosticContext = mapOf("源语言" to source),
                                                        )
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        ) { Text("删除", maxLines = 1) }
                                    }
                                } else {
                                    TextButton(
                                        onClick = {
                                            if (source == "en") {
                                                opusJob = scope.launch(Dispatchers.IO) {
                                                    try {
                                                        OpusMtModelManager.download(context)
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (_: Exception) {
                                                        // Exact error remains in model state and diagnostics.
                                                    }
                                                }
                                            } else {
                                                val newJob = scope.launch(Dispatchers.IO) {
                                                    try {
                                                        OpusMtAdditionalModelManager.download(context, source)
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (_: Exception) {
                                                        // Exact error remains in model state and diagnostics.
                                                    }
                                                }
                                                opusExtraJobs = opusExtraJobs + (source to newJob)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    ) { Text("下载", maxLines = 1) }
                                }
                            }
                            if (state.status == OpusMtStatus.DOWNLOADING ||
                                state.status == OpusMtStatus.VERIFYING
                            ) {
                                LinearProgressIndicator(
                                    progress = (state.progress / 100f).coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 4.dp),
                                    color = Color(0xFF3569D4),
                                )
                                Text(
                                    formatLocalModelBytes(state.downloadedBytes) + " / " +
                                        formatLocalModelBytes(state.totalBytes),
                                    modifier = Modifier.padding(start = 52.dp),
                                    color = Color(0xFF64748B),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (index < 3) HorizontalDivider(color = Color(0xFFE8ECF3))
                    }
                }
            } else if (detail == "singlePair") {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MlKitSourceLanguageControl(
                        manualSelected = isSinglePairManualSelection,
                        onModeSelected = { manual -> setSinglePairSelectionMode(manual) },
                    )
                    listOf(
                        "日语 → 简体中文" to "ja",
                        "韩语 → 简体中文" to "ko",
                        "俄语 → 简体中文" to "ru",
                    ).forEachIndexed { index, (language, source) ->
                        val selectedLanguage = isSinglePairManualSelection && singlePairManualSourceLanguage == source
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedLanguage) Color(0xFFF0F5FF) else Color.Transparent)
                                .padding(start = 4.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isSinglePairManualSelection) {
                                RadioButton(
                                    selected = selectedLanguage,
                                    onClick = { selectSinglePairLanguage(source) },
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                            Box(
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = isSinglePairManualSelection) { selectSinglePairLanguage(source) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    language,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selectedLanguage) Color(0xFF2858B8) else Color(0xFF172033),
                                    maxLines = 1,
                                )
                            }
                        }
                        if (index < 2) HorizontalDivider(color = Color(0xFFE8ECF3))
                    }

                    Divider(color = Color(0xFFE7EBF2))
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    "共用直译模型 · SMaLL-100",
                                    color = Color(0xFF172033),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    formatLocalModelSizeDetail(Small100ModelManager.TOTAL_BYTES),
                                    color = Color(0xFF64748B),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    when (small100State.status) {
                                        Small100Status.READY -> "已下载并验证"
                                        Small100Status.DOWNLOADING -> "下载中 ${small100State.progress}%"
                                        Small100Status.VERIFYING -> "正在验证模型"
                                        Small100Status.PAUSED -> "下载已暂停"
                                        Small100Status.FAILED -> small100State.error.ifBlank { "下载失败" }
                                        else -> "未下载"
                                    },
                                    color = when (small100State.status) {
                                        Small100Status.READY -> Color(0xFF25804A)
                                        Small100Status.DOWNLOADING, Small100Status.VERIFYING -> Color(0xFF3569D4)
                                        Small100Status.FAILED -> Color(0xFFB42318)
                                        else -> Color(0xFF64748B)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                )
                            }
                            if (small100Job?.isActive == true ||
                                small100State.status == Small100Status.DOWNLOADING ||
                                small100State.status == Small100Status.VERIFYING
                            ) {
                                TextButton(
                                    onClick = { small100Job?.cancel(); small100Job = null },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) { Text("暂停", maxLines = 1) }
                            } else if (small100State.status == Small100Status.READY) {
                                if (activeId == EngineFactory.ID_LOCAL_SINGLE_PAIR ||
                                    activeId == EngineFactory.ID_LOCAL_SMALL100
                                ) {
                                    Text(
                                        "使用中",
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        color = Color(0xFF25804A),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) { Small100ModelManager.delete(context) }
                                                } catch (error: Exception) {
                                                    AppLogStore.error(
                                                        context,
                                                        "LocalTranslationModel",
                                                        "删除单语言对直译模型失败",
                                                        error,
                                                        diagnosticContext = mapOf("模型" to "SMaLL-100"),
                                                    )
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    ) { Text("删除", maxLines = 1) }
                                }
                            } else {
                                TextButton(
                                    onClick = {
                                        small100Job = scope.launch(Dispatchers.IO) {
                                            try {
                                                Small100ModelManager.download(context)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                // Exact error remains in model state and diagnostics.
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) { Text("下载", maxLines = 1) }
                            }
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
                                    formatLocalModelBytes(small100State.totalBytes),
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
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
                    SourceLanguageMenu(
                        selected = sourceLanguageSelection,
                        languages = smallPairs,
                        expanded = sourceLanguageMenuExpanded,
                        onExpandedChange = { sourceLanguageMenuExpanded = it },
                        onSelected = { setSourceLanguage(it) },
                    )
                    val smallSourceCompatible = sourceLanguageSelection == "auto" ||
                        Small100LanguagePolicy.supportsSource(sourceLanguageSelection)
                    if (!smallSourceCompatible) {
                        Text(
                            "当前选择的源语言不受 SMaLL-100 支持。",
                            color = Color(0xFFB42318),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    "SMaLL-100 多语言直译模型",
                                    color = Color(0xFF172033),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    formatLocalModelSizeDetail(Small100ModelManager.TOTAL_BYTES),
                                    color = Color(0xFF64748B),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    when (small100State.status) {
                                        Small100Status.READY -> "已下载并通过验证"
                                        Small100Status.DOWNLOADING -> "下载中 ${small100State.progress}%"
                                        Small100Status.VERIFYING -> "正在验证模型"
                                        Small100Status.PAUSED -> "下载已暂停"
                                        Small100Status.FAILED -> small100State.error.ifBlank { "下载失败" }
                                        else -> "未下载"
                                    },
                                    color = when (small100State.status) {
                                        Small100Status.READY -> Color(0xFF25804A)
                                        Small100Status.DOWNLOADING, Small100Status.VERIFYING -> Color(0xFF3569D4)
                                        Small100Status.FAILED -> Color(0xFFB42318)
                                        else -> Color(0xFF64748B)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                )
                            }
                            if (small100Job?.isActive == true ||
                                small100State.status == Small100Status.DOWNLOADING ||
                                small100State.status == Small100Status.VERIFYING
                            ) {
                                TextButton(
                                    onClick = { small100Job?.cancel(); small100Job = null },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) { Text("暂停", maxLines = 1) }
                            } else if (small100State.status == Small100Status.READY) {
                                if (activeId == EngineFactory.ID_LOCAL_SMALL100 ||
                                    activeId == EngineFactory.ID_LOCAL_SINGLE_PAIR
                                ) {
                                    Text(
                                        "使用中",
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        color = Color(0xFF25804A),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) { Small100ModelManager.delete(context) }
                                                } catch (error: Exception) {
                                                    AppLogStore.error(
                                                        context, "LocalTranslationModel", "删除 SMaLL-100 模型失败", error,
                                                    )
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    ) { Text("删除", maxLines = 1) }
                                }
                            } else {
                                TextButton(
                                    onClick = {
                                        small100Job = scope.launch(Dispatchers.IO) {
                                            try {
                                                Small100ModelManager.download(context)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                // Exact error remains in model state and diagnostics.
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) { Text("下载", maxLines = 1) }
                            }
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
                                    formatLocalModelBytes(small100State.totalBytes),
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun MlKitSourceLanguageControl(
    manualSelected: Boolean,
    onModeSelected: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "源语言",
            color = Color(0xFF172033),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF3F6FC))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(false to "自动识别", true to "手动选择").forEach { (manual, label) ->
                Row(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (manualSelected == manual) Color(0xFF3569D4) else Color.Transparent)
                        .clickable { onModeSelected(manual) }
                        .padding(horizontal = 8.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        label,
                        color = if (manualSelected == manual) Color.White else Color(0xFF526176),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLanguageMenu(
    selected: String,
    languages: List<LocalTranslationPair>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("源语言", color = Color(0xFF172033), style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { onExpandedChange(true) }) {
                Text(
                    if (selected == "auto") "自动识别"
                    else languages.firstOrNull { it.source == selected }?.name?.substringBefore(" →") ?: selected,
                )
                Icon(Icons.Filled.ExpandMore, contentDescription = "选择源语言")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                DropdownMenuItem(text = { Text("自动识别") }, onClick = { onSelected("auto") })
                languages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.name.substringBefore(" →")) },
                        onClick = { onSelected(language.source) },
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
    onSelect: () -> Unit,
    onManage: () -> Unit,
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
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp),
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
            if (selected) Text("当前翻译引擎", style = MaterialTheme.typography.labelSmall, color = Color(0xFF3569D4))
        }
        TextButton(onClick = onManage, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            Text("管理", maxLines = 1)
        }
    }
}

private fun formatLocalModelSizeDetail(bytes: Long): String =
    "下载大小 " + String.format(Locale.ROOT, "%,d 字节 · %.2f MB", bytes, bytes / 1_000_000.0)

private fun formatLocalModelBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1_000_000.0
    return String.format(Locale.ROOT, "%.2f MB", mb)
}

private fun formatSmall100Bytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / 1_000_000f
    return if (megabytes >= 100f) "${megabytes.toInt()} MB"
    else String.format(Locale.ROOT, "%.1f MB", megabytes)
}
