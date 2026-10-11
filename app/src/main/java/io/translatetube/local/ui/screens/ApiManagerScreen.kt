package io.translatetube.local.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.translatetube.local.data.ApiProfile
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.translate.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.ui.theme.*
import kotlinx.coroutines.withContext

@Composable
fun ApiManagerScreen(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(ctx) }
    var profiles by remember { mutableStateOf<List<ApiProfile>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var activeId by remember { mutableStateOf(prefs.getLong("active_api_id", -1L)) }

    // 手动切换翻译引擎：写 prefs 即刻生效（下次翻译起用新源）
    fun setActive(id: Long) {
        activeId = id
        prefs.edit().putLong("active_api_id", id).apply()
    }

    LaunchedEffect(Unit) {
        try {
            db.apiProfileDao().all().collect { list ->
                val supportedProfiles = list.filterNot { it.type == "AZURE" || it.type == "BAIDU" }
                profiles = supportedProfiles
                loadError = null
                // 已删除、停用或已移除的引擎不能继续作为当前引擎，自动回退到本地引擎。
                if (activeId >= 0L && supportedProfiles.none { it.id == activeId && it.enabled }) {
                    setActive(-1L)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // 数据库/旧版本结构异常要显示出来，不应因 LaunchedEffect 未处理的异常带崩整个页面。
            android.util.Log.e("ApiManagerScreen", "读取 API 配置失败", e)
            loadError = "API 配置暂时无法读取，请返回后重试。"
        }
    }

    Column(Modifier.fillMaxSize().background(BgDark).systemBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = OnBg) }
            Text("翻译引擎", style = MaterialTheme.typography.titleLarge, color = OnBg, modifier = Modifier.weight(1f))
            Button(
                onClick = { adding = true },
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = androidx.compose.ui.graphics.Color.White),
            ) { Text("+ 添加") }
        }
        Spacer(Modifier.height(8.dp))

        loadError?.let {
            Text(it, color = WarnYellow, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        var googleExpanded by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (activeId == -1L || activeId == -2L) SurfaceHigh else SurfaceDark)
                .clickable {
                    setActive(-2L)
                    googleExpanded = !googleExpanded
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoogleTranslateIcon()
            Spacer(Modifier.width(10.dp))
            RadioButton(
                selected = activeId == -1L || activeId == -2L,
                onClick = { setActive(-2L) },
                colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
            )
            Text("Google 翻译", color = OnBg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (activeId == -1L || activeId == -2L) {
                Text("已选", color = BrandBright, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (googleExpanded) androidx.compose.material.icons.Icons.Default.ExpandLess
                    else androidx.compose.material.icons.Icons.Default.ExpandMore,
                contentDescription = if (googleExpanded) "收起离线语言包" else "展开离线语言包",
                tint = SubText,
            )
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (googleExpanded) item { GoogleTranslateSection() }
            items(profiles, key = { it.id }) { p ->
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (activeId == p.id && p.enabled) SurfaceHigh else SurfaceDark)
                        .clickable(enabled = p.enabled) { setActive(p.id) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = activeId == p.id,
                            onClick = { if (p.enabled) setActive(p.id) },
                            colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
                        )
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(p.name, color = OnBg, style = MaterialTheme.typography.bodyLarge)
                        }
                        Switch(
                            checked = p.enabled,
                            onCheckedChange = { en ->
                                scope.launch {
                                    db.apiProfileDao().upsert(p.copy(enabled = en))
                                    if (!en && activeId == p.id) setActive(-1L)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = BrandBright),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { editing = p }) { Text("编辑配置", color = BrandBright) }
                        TextButton(onClick = {
                            if (activeId == p.id) setActive(-1L)
                            scope.launch { db.apiProfileDao().delete(p.id) }
                        }) { Text("删除", color = DangerRed) }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        EditDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { p ->
                scope.launch(Dispatchers.IO) {
                    db.apiProfileDao().upsert(p)
                    withContext(Dispatchers.Main) { adding = false; editing = null }
                }
            },
        )
    }
}

private fun typeLabel(t: String) = when (t) {
    "OPENAI" -> "OpenAI 兼容大模型"; "GEMINI" -> "Google Gemini 大模型"
    "GCLOUD" -> "Google Cloud Translation 官方翻译 API（需密钥）"
    else -> t
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(initial: ApiProfile?, onDismiss: () -> Unit, onSave: (ApiProfile) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "OPENAI") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember {
        mutableStateOf(initial?.let { Crypto.decrypt(it.apiKey) } ?: "")
    }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var region by remember { mutableStateOf(initial?.region ?: "") }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var pulling by remember { mutableStateOf(false) }
    var pullError by remember { mutableStateOf<String?>(null) }

    val needsBaseUrl = type == "OPENAI"
    // Azure Translator 同样需要订阅密钥；区域不能替代 API Key。
    val needsKey = true
    val fixedEngine = type == "GCLOUD"

    val configuration = LocalConfiguration.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scope = rememberCoroutineScope()
        Column(
            Modifier.fillMaxWidth(0.94f)
                .heightIn(max = (configuration.screenHeightDp * 0.84f).dp)
                .clip(RoundedCornerShape(20.dp)).background(SurfaceDark)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(if (initial == null) "添加翻译 API" else "编辑 API", style = MaterialTheme.typography.titleLarge, color = OnBg)
            Spacer(Modifier.height(10.dp))

            Labeled("名称") { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("如 DeepSeek") }) }

            var typeMenuExpanded by remember { mutableStateOf(false) }
            val typeOptions = listOf(
                "OPENAI" to "OpenAI 兼容",
                "GEMINI" to "Gemini 大模型",
                "GCLOUD" to "Google 翻译 API",
            )
            Labeled("服务类型") {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = typeOptions.firstOrNull { it.first == type }?.second ?: "OpenAI 兼容",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().clickable { typeMenuExpanded = true },
                        trailingIcon = {
                            IconButton(onClick = { typeMenuExpanded = !typeMenuExpanded }) {
                                Icon(
                                    imageVector = if (typeMenuExpanded) androidx.compose.material.icons.Icons.Default.ExpandLess
                                        else androidx.compose.material.icons.Icons.Default.ExpandMore,
                                    contentDescription = "选择服务类型",
                                    tint = SubText,
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandBright,
                            unfocusedBorderColor = SurfaceHigh,
                            focusedTextColor = OnBg,
                            unfocusedTextColor = OnBg,
                        ),
                    )
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.86f).heightIn(max = 240.dp)
                            .background(SurfaceDark),
                    ) {
                        typeOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (type == value) BrandBright else OnBg) },
                                trailingIcon = {
                                    if (type == value) Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                        contentDescription = null,
                                        tint = BrandBright,
                                    )
                                },
                                onClick = {
                                    type = value
                                    models = null
                                    model = ""
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            if (needsBaseUrl) Labeled("Base URL") {
                OutlinedTextField(
                    baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("https://api.deepseek.com（填到域名即可）") },
                )
            }
            if (needsKey) Labeled("API Key") {
                OutlinedTextField(
                    apiKey, { apiKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = {
                        Text(
                            when (type) {
                                "GCLOUD" -> "Google Cloud API Key"
                                else -> "API Key（本地加密存储）"
                            }
                        )
                    },
                )
            }

            // —— 模型拉取（OpenAI兼容 / Gemini）——
            if (!fixedEngine) {
                Labeled("翻译模型") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("填写模型名称") },
                        )
                        Button(
                            onClick = {
                                pulling = true; pullError = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val prof = ApiProfile(
                                            id = initial?.id ?: 0, name = name.ifEmpty { "tmp" }, type = type,
                                            baseUrl = baseUrl, apiKey = Crypto.encrypt(apiKey), model = model,
                                            region = region, enabled = true,
                                        )
                                        val list = EngineFactory.fromProfile(prof).listModels()
                                            ?: throw IllegalStateException("该类型不支持拉取")
                                        withContext(Dispatchers.Main) { models = list; pulling = false }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            pullError = e.message ?: "拉取失败"; pulling = false
                                        }
                                    }
                                }
                            },
                            enabled = !pulling && (apiKey.isNotBlank() || !needsKey),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = androidx.compose.ui.graphics.Color.White),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (pulling) "拉取中…" else "↓ 拉取可用模型") }
                    }
                }
                pullError?.let {
                    Text("拉取失败：$it\n可手动填写模型名继续。", color = WarnYellow, style = MaterialTheme.typography.bodySmall)
                }
                models?.let { list ->
                    Text("选择模型（共 ${list.size} 个）：", color = SubText, style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.height(220.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceHigh)) {
                        LazyColumn(Modifier.fillMaxSize().padding(6.dp)) {
                            items(list) { m ->
                                Text(
                                    m,
                                    color = if (m == model) BrandBright else OnBg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (m == model) Brand else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable { model = m }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("取消", color = SubText) }
                Button(
                    onClick = {
                        val id = initial?.id ?: 0
                        onSave(
                            ApiProfile(
                                id = id, name = name.ifEmpty { typeLabel(type) }, type = type,
                                baseUrl = baseUrl.trim(), apiKey = Crypto.encrypt(apiKey.trim()),
                                model = model.trim(), region = region.trim(), enabled = true,
                            )
                        )
                    },
                    enabled = (!needsBaseUrl || baseUrl.isNotBlank()) &&
                        apiKey.isNotBlank() &&
                        (fixedEngine || model.isNotBlank()),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = androidx.compose.ui.graphics.Color.White),
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, field: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = SubText, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        field()
    }
}
