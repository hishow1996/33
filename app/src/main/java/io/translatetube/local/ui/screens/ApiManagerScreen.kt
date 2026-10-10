package io.translatetube.local.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.apiProfileDao().all().collect { profiles = it }
    }

    Column(Modifier.fillMaxSize().background(BgDark).systemBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = OnBg) }
            Text("翻译 API", style = MaterialTheme.typography.titleLarge, color = OnBg, modifier = Modifier.weight(1f))
            Button(
                onClick = { adding = true },
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBg),
            ) { Text("+ 添加") }
        }
        Text(
            "排最前的启用项为活跃翻译源。OpenAI 兼容类型支持 DeepSeek/智谱/Kimi/Ollama 等所有兼容服务。",
            color = SubText, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceDark).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = OnBg, style = MaterialTheme.typography.bodyLarge)
                        Text("${typeLabel(p.type)} · ${p.model.ifEmpty { "未选模型" }}", color = SubText, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = p.enabled,
                        onCheckedChange = { en -> scope.launch { db.apiProfileDao().upsert(p.copy(enabled = en)) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = BrandBright),
                    )
                    TextButton(onClick = { editing = p }) { Text("编辑", color = BrandBright) }
                    TextButton(onClick = { scope.launch { db.apiProfileDao().delete(p.id) } }) {
                        Text("删", color = DangerRed)
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
    "OPENAI" -> "OpenAI 兼容"; "GEMINI" -> "Google Gemini"
    "GCLOUD" -> "谷歌翻译 API"; "AZURE" -> "微软 Azure"
    else -> t
}

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
    val needsKey = type != "AZURE"
    val needsRegion = type == "AZURE"
    val fixedEngine = type == "GCLOUD" || type == "AZURE"

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val scope = rememberCoroutineScope()
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(SurfaceDark).padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(if (initial == null) "添加翻译 API" else "编辑 API", style = MaterialTheme.typography.titleLarge, color = OnBg)
            Spacer(Modifier.height(10.dp))

            Labeled("名称") { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("如 DeepSeek") }) }

            Labeled("类型") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("OPENAI" to "OpenAI兼容", "GEMINI" to "Gemini", "GCLOUD" to "谷歌", "AZURE" to "Azure").forEach { (v, label) ->
                        FilterChip(
                            selected = type == v,
                            onClick = { type = v; models = null; model = "" },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand, selectedLabelColor = OnBg,
                            ),
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
                    apiKey, { apiKey = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("sk-…（本地加密存储）") },
                )
            }
            if (needsRegion) Labeled("区域") {
                OutlinedTextField(region, { region = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("如 eastasia") })
            }

            // —— 模型拉取（OpenAI兼容 / Gemini）——
            if (!fixedEngine) {
                Labeled("翻译模型") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            model, { model = it }, Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("手动填或点右侧拉取") },
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
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBg),
                        ) { Text(if (pulling) "拉取中…" else "⬇ 拉取模型") }
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
                    enabled = name.isNotBlank() || type.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBg),
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
