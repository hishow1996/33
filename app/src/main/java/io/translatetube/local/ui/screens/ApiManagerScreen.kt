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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

    Column(
        Modifier.fillMaxSize().background(BgDark).systemBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp)).background(Color(0xFFF2F6FC))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = OnBg)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("API 管理", style = MaterialTheme.typography.titleLarge, color = OnBg)
                Text("配置翻译服务与模型", style = MaterialTheme.typography.bodySmall, color = SubText)
            }
            Surface(
                onClick = { adding = true },
                shape = CircleShape,
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineSoft),
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "添加 API", tint = BrandBright)
                }
            }
        }

        loadError?.let {
            Text(it, color = WarnYellow, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        var googleExpanded by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .clickable { googleExpanded = !googleExpanded }
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Google 翻译", color = OnBg, style = MaterialTheme.typography.titleMedium)
                Text("内置翻译 · 离线语言包可选", color = SubText, style = MaterialTheme.typography.bodySmall)
            }
            if (activeId == -1L || activeId == -2L) {
                Text("已选", color = BrandBright, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (googleExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (googleExpanded) "收起 Google 翻译设置" else "展开 Google 翻译设置",
                tint = SubText,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (googleExpanded) item { GoogleTranslateSection() }
            if (profiles.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceDark)
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("还没有 API 配置", color = OnBg, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "也可以直接使用上方的 Google 翻译。",
                            color = SubText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { adding = true },
                            shape = RoundedCornerShape(11.dp),
                        ) { Text("添加 API") }
                    }
                }
            }
            items(profiles, key = { it.id }) { p ->
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .clickable(enabled = p.enabled) { setActive(p.id) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val selectedProfile = activeId == p.id && p.enabled
                    var profileMenuExpanded by remember(p.id) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedProfile) {
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(SurfaceHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("API", color = BrandBright, style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            RadioButton(
                                selected = activeId == p.id,
                                onClick = { if (p.enabled) setActive(p.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
                            )
                        }
                        Column(
                            Modifier.weight(1f).padding(start = if (selectedProfile) 12.dp else 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(p.name, color = OnBg, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (selectedProfile) "当前服务 · ${typeLabel(p.type)}" else typeLabel(p.type),
                                color = SubText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                        if (selectedProfile) {
                            Text("✓", color = Color(0xFF25965B), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(6.dp))
                            Box {
                                Text(
                                    "•••",
                                    color = SubText,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.clickable { profileMenuExpanded = true }
                                        .padding(horizontal = 5.dp, vertical = 8.dp),
                                )
                                DropdownMenu(
                                    expanded = profileMenuExpanded,
                                    onDismissRequest = { profileMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("编辑配置") },
                                        onClick = { profileMenuExpanded = false; editing = p },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("停用服务") },
                                        onClick = {
                                            profileMenuExpanded = false
                                            scope.launch {
                                                db.apiProfileDao().upsert(p.copy(enabled = false))
                                                if (activeId == p.id) setActive(-1L)
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除配置") },
                                        onClick = {
                                            profileMenuExpanded = false
                                            if (activeId == p.id) setActive(-1L)
                                            scope.launch { db.apiProfileDao().delete(p.id) }
                                        },
                                    )
                                }
                            }
                        } else {
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
                    }
                    if (activeId == p.id && p.enabled) {
                        var inlineUrl by remember(p.id, p.baseUrl) { mutableStateOf(p.baseUrl) }
                        var inlineKey by remember(p.id, p.apiKey) { mutableStateOf(Crypto.decrypt(p.apiKey)) }
                        var inlineModel by remember(p.id, p.model) { mutableStateOf(p.model) }
                        var showInlineKey by remember(p.id) { mutableStateOf(false) }
                        var checkingConnection by remember(p.id) { mutableStateOf(false) }
                        var connectionMessage by remember(p.id) { mutableStateOf<String?>(null) }

                        HorizontalDivider(color = OutlineSoft, modifier = Modifier.padding(vertical = 5.dp))
                        if (p.type == "OPENAI") {
                            Text("服务地址", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(5.dp))
                            OutlinedTextField(
                                value = inlineUrl,
                                onValueChange = { inlineUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(15.dp),
                                placeholder = { Text("https://api.example.com/v1") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandBright,
                                    unfocusedBorderColor = Color(0xFFF0F3F8),
                                    focusedContainerColor = Color(0xFFF6F8FC),
                                    unfocusedContainerColor = Color(0xFFF6F8FC),
                                ),
                            )
                            Spacer(Modifier.height(9.dp))
                        }
                        Text("API Key", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(5.dp))
                        OutlinedTextField(
                            value = inlineKey,
                            onValueChange = { inlineKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(15.dp),
                            visualTransformation = if (showInlineKey) VisualTransformation.None else PasswordVisualTransformation(),
                            placeholder = { Text("输入 API Key") },
                            trailingIcon = {
                                TextButton(
                                    onClick = { showInlineKey = !showInlineKey },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text(if (showInlineKey) "隐藏" else "显示", color = BrandBright) }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandBright,
                                unfocusedBorderColor = Color(0xFFF0F3F8),
                                focusedContainerColor = Color(0xFFF6F8FC),
                                unfocusedContainerColor = Color(0xFFF6F8FC),
                            ),
                        )
                        if (p.type != "GCLOUD") {
                            Spacer(Modifier.height(9.dp))
                            Text("模型名称", color = OnBg, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(5.dp))
                            OutlinedTextField(
                                value = inlineModel,
                                onValueChange = { inlineModel = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(15.dp),
                                placeholder = { Text("选择或填写模型") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandBright,
                                    unfocusedBorderColor = Color(0xFFF0F3F8),
                                    focusedContainerColor = Color(0xFFF6F8FC),
                                    unfocusedContainerColor = Color(0xFFF6F8FC),
                                ),
                            )
                        }
                        if (connectionMessage != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(connectionMessage!!, color = if (connectionMessage!!.startsWith("连接成功")) Color(0xFF21945A) else DangerRed, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(11.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f).height(48.dp),
                                enabled = !checkingConnection && p.type != "GCLOUD",
                                onClick = {
                                    checkingConnection = true
                                    connectionMessage = null
                                    val testProfile = p.copy(
                                        baseUrl = inlineUrl.trim(),
                                        apiKey = Crypto.encrypt(inlineKey.trim()),
                                        model = inlineModel.trim(),
                                    )
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val available = EngineFactory.fromProfile(testProfile).listModels()
                                            withContext(Dispatchers.Main) {
                                                connectionMessage = if (available != null) {
                                                    "连接成功 · 返回 " + available.size + " 个模型"
                                                } else {
                                                    "接口未返回模型列表，请确认服务配置"
                                                }
                                                checkingConnection = false
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                connectionMessage = "连接失败 · " + (e.message ?: "请检查地址和 API Key")
                                                checkingConnection = false
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright),
                            ) { Text(if (checkingConnection) "测试中…" else "测试连接") }
                            Button(
                                modifier = Modifier.weight(1f).height(48.dp),
                                enabled = (p.type != "OPENAI" || inlineUrl.isNotBlank()) &&
                                    inlineKey.isNotBlank() && (p.type == "GCLOUD" || inlineModel.isNotBlank()),
                                onClick = {
                                    val saved = p.copy(
                                        baseUrl = inlineUrl.trim(),
                                        apiKey = Crypto.encrypt(inlineKey.trim()),
                                        model = inlineModel.trim(),
                                    )
                                    scope.launch(Dispatchers.IO) {
                                        db.apiProfileDao().upsert(saved)
                                        withContext(Dispatchers.Main) {
                                            connectionMessage = "配置已保存"
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = Color.White),
                            ) { Text("保存配置") }
                        }
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
    var showApiKey by remember { mutableStateOf(false) }
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

            var typePickerExpanded by remember { mutableStateOf(false) }
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
                        modifier = Modifier.fillMaxWidth().clickable { typePickerExpanded = true },
                        trailingIcon = {
                            IconButton(onClick = { typePickerExpanded = !typePickerExpanded }) {
                                Text(
                                    if (typePickerExpanded) "⌃" else "⌄",
                                    color = SubText,
                                    style = MaterialTheme.typography.titleLarge,
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
                    if (typePickerExpanded) {
                        AlertDialog(
                            onDismissRequest = { typePickerExpanded = false },
                            title = { Text("选择服务类型", color = OnBg, style = MaterialTheme.typography.titleLarge) },
                            text = {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    items(typeOptions, key = { it.first }) { (value, label) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (type == value) SurfaceHigh else SurfaceDark)
                                                .clickable {
                                                    type = value
                                                    models = null
                                                    model = ""
                                                    typePickerExpanded = false
                                                }
                                                .padding(horizontal = 12.dp, vertical = 13.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = type == value,
                                                onClick = {
                                                    type = value
                                                    models = null
                                                    model = ""
                                                    typePickerExpanded = false
                                                },
                                                colors = RadioButtonDefaults.colors(selectedColor = BrandBright),
                                            )
                                            Text(
                                                label,
                                                color = if (type == value) BrandBright else OnBg,
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (type == value) {
                                                Text("✓", color = BrandBright, style = MaterialTheme.typography.titleMedium)
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { typePickerExpanded = false }) {
                                    Text("完成", color = BrandBright)
                                }
                            },
                            containerColor = SurfaceDark,
                        )
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
                    apiKey,
                    { apiKey = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    placeholder = {
                        Text(
                            when (type) {
                                "GCLOUD" -> "Google Cloud API Key"
                                else -> "API Key（本地加密存储）"
                            }
                        )
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { showApiKey = !showApiKey },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text(if (showApiKey) "隐藏" else "显示") }
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
