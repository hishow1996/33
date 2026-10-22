package io.translatetube.local.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
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
import android.net.Uri
import java.io.File
import java.util.Locale
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
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
import io.translatetube.local.translate.FreeModelDefaults
import io.translatetube.local.translate.GeminiEngine
import io.translatetube.local.translate.OpenAiCompatEngine
import android.widget.Toast
import io.translatetube.local.ui.theme.*
import kotlinx.coroutines.withContext

/** 内置的免密钥在线翻译引擎卡片：点选即切换，右侧可测试连通性。 */
@Composable
private fun BuiltinWebEngineCard(
    title: String,
    logoResId: Int,
    initial: String,
    imageLoader: ImageLoader,
    selected: Boolean,
    testing: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
) {
    val cardColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFEAF1FF) else Color.White,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "builtinEngineCardColor",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color(0xFF3569D4) else Color(0xFFE3E9F2),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "builtinEngineBorderColor",
    )
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(15.dp))
                .background(if (selected) Color(0xFFEAF1FF) else Color(0xFFF7F9FC)),
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = logoResId,
                imageLoader = imageLoader,
                contentDescription = "$title 官方 Logo",
                modifier = Modifier.size(42.dp),
                contentScale = ContentScale.Fit,
                loading = {
                    Text(
                        initial,
                        color = Color(0xFF3569D4),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                },
                error = {
                    Text(
                        initial,
                        color = Color(0xFF3569D4),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                },
                success = { SubcomposeAsyncImageContent() },
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                color = OnBg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(animationSpec = tween(160)) +
                expandHorizontally(animationSpec = tween(190, easing = FastOutSlowInEasing)),
            exit = shrinkHorizontally(animationSpec = tween(130, easing = FastOutSlowInEasing)) +
                fadeOut(animationSpec = tween(100)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFF3569D4),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "正在使用",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        TextButton(onClick = onTest, enabled = !testing) {
            Text(if (testing) "测试中…" else "测试")
        }
    }
}

@Composable
fun ApiManagerScreen(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val ctx = LocalContext.current
    val providerLogoImageLoader = remember(ctx.applicationContext) {
        ImageLoader.Builder(ctx.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    DisposableEffect(providerLogoImageLoader) {
        onDispose { providerLogoImageLoader.shutdown() }
    }
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(ctx) }
    var profiles by remember { mutableStateOf<List<ApiProfile>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var activeId by remember { mutableStateOf(prefs.getLong("active_api_id", -1L)) }

    var geminiModel by remember {
        mutableStateOf(prefs.getString("free_gemini_model", FreeModelDefaults.GEMINI_MODEL) ?: FreeModelDefaults.GEMINI_MODEL)
    }
    var geminiKeyConfigured by remember {
        mutableStateOf(prefs.getString("free_gemini_key", "").orEmpty().isNotBlank())
    }
    var deepSeekModel by remember {
        mutableStateOf(prefs.getString("free_deepseek_model", FreeModelDefaults.DEEPSEEK_MODEL) ?: FreeModelDefaults.DEEPSEEK_MODEL)
    }
    var deepSeekKeyConfigured by remember {
        mutableStateOf(prefs.getString("free_deepseek_key", "").orEmpty().isNotBlank())
    }
    var configuringFreeModel by remember { mutableStateOf<String?>(null) }
    var testingFreeModel by remember { mutableStateOf<String?>(null) }

    fun refreshFreeModelPrefs() {
        geminiModel = prefs.getString("free_gemini_model", FreeModelDefaults.GEMINI_MODEL) ?: FreeModelDefaults.GEMINI_MODEL
        geminiKeyConfigured = prefs.getString("free_gemini_key", "").orEmpty().isNotBlank()
        deepSeekModel = prefs.getString("free_deepseek_model", FreeModelDefaults.DEEPSEEK_MODEL) ?: FreeModelDefaults.DEEPSEEK_MODEL
        deepSeekKeyConfigured = prefs.getString("free_deepseek_key", "").orEmpty().isNotBlank()
    }

    // 手动切换翻译引擎：写 prefs 即刻生效（下次翻译起用新源）
    fun setActive(id: Long) {
        activeId = id
        prefs.edit().putLong("active_api_id", id).apply()
    }

    fun testFreeModel(targetId: Long, name: String) {
        scope.launch(Dispatchers.IO) {
            testingFreeModel = name
            try {
                val engine = EngineFactory.getBuiltinEngine(targetId, ctx)
                    ?: throw IllegalStateException("未能创建引擎")
                val started = System.currentTimeMillis()
                engine.testConnection()
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "$name 连接成功 · ${elapsed}ms", Toast.LENGTH_SHORT).show()
                    testingFreeModel = null
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val msg = formatConnectionErrorMessage(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "$name $msg", Toast.LENGTH_LONG).show()
                    testingFreeModel = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (activeId == EngineFactory.ID_FREE_GEMINI ||
            activeId == EngineFactory.ID_FREE_DEEPSEEK
        ) {
            setActive(-1L)
        }
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
            }
            IconButton(
                onClick = { adding = true },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加 API", tint = BrandBright)
            }
        }

        loadError?.let {
            Text(it, color = WarnYellow, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        var googleExpanded by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Google 翻译 (内置混合引擎)
            item(key = "builtin_google") {
                val isGoogleActive = activeId == -1L || activeId == -2L
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isGoogleActive) Color(0xFFEAF1FF) else Color.White)
                        .border(
                            width = if (isGoogleActive) 1.5.dp else 1.dp,
                            color = if (isGoogleActive) Color(0xFF3569D4) else Color(0xFFE3E9F2),
                            shape = RoundedCornerShape(18.dp),
                        )
                        .clickable { setActive(-1L) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(15.dp))
                            .background(if (isGoogleActive) Color(0xFFEAF1FF) else Color(0xFFF7F9FC)),
                        contentAlignment = Alignment.Center,
                    ) {
                        GoogleTranslateIcon(providerLogoImageLoader)
                    }
                    Text(
                        "Google 翻译",
                        color = OnBg,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    if (isGoogleActive) {
                        Surface(
                            color = Color(0xFF3569D4),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                "正在使用",
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = { googleExpanded = !googleExpanded },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (googleExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (googleExpanded) "收起离线语言包" else "展开离线语言包",
                            tint = SubText,
                        )
                    }
                }
            }

            if (googleExpanded) item(key = "google_section") {
                GoogleTranslateSection()
            }

            // 免密钥在线引擎：微软翻译、百度翻译（非正式网页接口，可能被限流或调整）
            item(key = "builtin_microsoft") {
                BuiltinWebEngineCard(
                    title = "微软翻译",
                    logoResId = io.translatetube.local.R.raw.microsoft_translator_logo,
                    initial = "微",
                    imageLoader = providerLogoImageLoader,
                    selected = activeId == EngineFactory.ID_MICROSOFT,
                    testing = testingFreeModel == "微软翻译",
                    onSelect = { setActive(EngineFactory.ID_MICROSOFT) },
                    onTest = { testFreeModel(EngineFactory.ID_MICROSOFT, "微软翻译") },
                )
            }
            item(key = "builtin_baidu") {
                BuiltinWebEngineCard(
                    title = "百度翻译",
                    logoResId = io.translatetube.local.R.raw.baidu_translate_logo,
                    initial = "百",
                    imageLoader = providerLogoImageLoader,
                    selected = activeId == EngineFactory.ID_BAIDU,
                    testing = testingFreeModel == "百度翻译",
                    onSelect = { setActive(EngineFactory.ID_BAIDU) },
                    onTest = { testFreeModel(EngineFactory.ID_BAIDU, "百度翻译") },
                )
            }

            // 2. 用户自定义添加的 API 列表
            items(profiles, key = { it.id }) { p ->
                val selectedProfile = activeId == p.id && p.enabled
                var profileMenuExpanded by remember(p.id) { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selectedProfile) Color(0xFFEAF1FF) else Color.White)
                        .border(
                            width = if (selectedProfile) 1.5.dp else 1.dp,
                            color = if (selectedProfile) Color(0xFF3569D4) else Color(0xFFE3E9F2),
                            shape = RoundedCornerShape(18.dp),
                        )
                        .clickable(enabled = p.enabled) { setActive(p.id) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ApiProviderLogo(profile = p, selected = selectedProfile, imageLoader = providerLogoImageLoader)
                    Text(
                        p.name,
                        color = if (p.enabled) OnBg else SubText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    if (selectedProfile) {
                        Surface(
                            color = Color(0xFF3569D4),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                "正在使用",
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Box {
                        IconButton(
                            onClick = { profileMenuExpanded = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Text("•••", color = SubText, style = MaterialTheme.typography.titleMedium)
                        }
                        DropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false },
                        ) {
                            if (!selectedProfile && p.enabled) {
                                DropdownMenuItem(
                                    text = { Text("设为当前服务") },
                                    onClick = {
                                        profileMenuExpanded = false
                                        setActive(p.id)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("编辑配置") },
                                onClick = {
                                    profileMenuExpanded = false
                                    editing = p
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("测试连接") },
                                onClick = {
                                    profileMenuExpanded = false
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val started = System.currentTimeMillis()
                                            EngineFactory.fromProfile(p).testConnection()
                                            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "${p.name} 连接成功 · ${elapsed}ms", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (c: CancellationException) {
                                            throw c
                                        } catch (e: Exception) {
                                            val msg = apiConnectionFailureMessage(p, e)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(ctx, "${p.name} $msg", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (p.enabled) "停用服务" else "启用服务") },
                                onClick = {
                                    profileMenuExpanded = false
                                    scope.launch {
                                        val newEnabled = !p.enabled
                                        db.apiProfileDao().upsert(p.copy(enabled = newEnabled))
                                        if (!newEnabled && activeId == p.id) setActive(-1L)
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
                }
            }
        }
    }

    if (configuringFreeModel != null) {
        FreeModelConfigDialog(
            type = configuringFreeModel!!,
            onDismiss = { configuringFreeModel = null },
            onSaved = { refreshFreeModelPrefs() },
        )
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

private const val GEMINI_LOGO_PATH =
    "M20.616 10.835a14.147 14.147 0 01-4.45-3.001 14.111 14.111 0 01-3.678-6.452.503.503 0 00-.975 0 14.134 14.134 0 01-3.679 6.452 14.155 14.155 0 01-4.45 3.001c-.65.28-1.318.505-2.002.678a.502.502 0 000 .975c.684.172 1.35.397 2.002.677a14.147 14.147 0 014.45 3.001 14.112 14.112 0 013.679 6.453.502.502 0 00.975 0c.172-.685.397-1.351.677-2.003a14.145 14.145 0 013.001-4.45 14.113 14.113 0 016.453-3.678.503.503 0 000-.975 13.245 13.245 0 01-2.003-.678z"

private const val DEEPSEEK_LOGO_PATH =
    "M23.748 4.482c-.254-.124-.364.113-.512.234-.051.039-.094.09-.137.136-.372.397-.806.657-1.373.626-.829-.046-1.537.214-2.163.848-.133-.782-.575-1.248-1.247-1.548-.352-.156-.708-.311-.955-.65-.172-.241-.219-.51-.305-.774-.055-.16-.11-.323-.293-.35-.2-.031-.278.136-.356.276-.313.572-.434 1.202-.422 1.84.027 1.436.633 2.58 1.838 3.393.137.093.172.187.129.323-.082.28-.18.552-.266.833-.055.179-.137.217-.329.14a5.526 5.526 0 01-1.736-1.18c-.857-.828-1.631-1.742-2.597-2.458a11.365 11.365 0 00-.689-.471c-.985-.957.13-1.743.388-1.836.27-.098.093-.432-.779-.428-.872.004-1.67.295-2.687.684a3.055 3.055 0 01-.465.137 9.597 9.597 0 00-2.883-.102c-1.885.21-3.39 1.102-4.497 2.623C.082 8.606-.231 10.684.152 12.85c.403 2.284 1.569 4.175 3.36 5.653 1.858 1.533 3.997 2.284 6.438 2.14 1.482-.085 3.133-.284 4.994-1.86.47.234.962.327 1.78.397.63.059 1.236-.03 1.705-.128.735-.156.684-.837.419-.961-2.155-1.004-1.682-.595-2.113-.926 1.096-1.296 2.746-2.642 3.392-7.003.05-.347.007-.565 0-.845-.004-.17.035-.237.23-.256a4.173 4.173 0 001.545-.475c1.396-.763 1.96-2.015 2.093-3.517.02-.23-.004-.467-.247-.588zM11.581 18c-2.089-1.642-3.102-2.183-3.52-2.16-.392.024-.321.471-.235.763.09.288.207.486.371.739.114.167.192.416-.113.603-.673.416-1.842-.14-1.897-.167-1.361-.802-2.5-1.86-3.301-3.307-.774-1.393-1.224-2.887-1.298-4.482-.02-.386.093-.522.477-.592a4.696 4.696 0 011.529-.039c2.132.312 3.946 1.265 5.468 2.774.868.86 1.525 1.887 2.202 2.891.72 1.066 1.494 2.082 2.48 2.914.348.292.625.514.891.677-.802.09-2.14.11-3.054-.614zm1-6.44a.306.306 0 01.415-.287.302.302 0 01.2.288.306.306 0 01-.31.307.303.303 0 01-.304-.308zm3.11 1.596c-.2.081-.399.151-.59.16a1.245 1.245 0 01-.798-.254c-.274-.23-.47-.358-.552-.758a1.73 1.73 0 01.016-.588c.07-.327-.008-.537-.239-.727-.187-.156-.426-.199-.688-.199a.559.559 0 01-.254-.078c-.11-.054-.2-.19-.114-.358.028-.054.16-.186.192-.21.356-.202.767-.136 1.146.016.352.144.618.408 1.001.782.391.451.462.576.685.914.176.265.336.537.445.848.067.195-.019.354-.25.452z"

@Composable
fun GeminiLogo(selected: Boolean = false) {
    val geminiPath = remember {
        PathParser().parsePathString(GEMINI_LOGO_PATH).toPath().apply {
            fillType = PathFillType.EvenOdd
        }
    }
    Box(
        Modifier.size(52.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color(0xFFEAF1FF) else Color(0xFFF7F9FC)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val scaleX = size.width / 24f
            val scaleY = size.height / 24f
            withTransform({ scale(scaleX, scaleY, pivot = Offset.Zero) }) {
                drawPath(geminiPath, color = Color(0xFF8E75B2))
            }
        }
    }
}

@Composable
fun DeepSeekLogo(selected: Boolean = false) {
    val deepSeekPath = remember {
        PathParser().parsePathString(DEEPSEEK_LOGO_PATH).toPath().apply {
            fillType = PathFillType.EvenOdd
        }
    }
    Box(
        Modifier.size(52.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color(0xFFEAF1FF) else Color(0xFFF7F9FC)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val scaleX = size.width / 24f
            val scaleY = size.height / 24f
            withTransform({ scale(scaleX, scaleY, pivot = Offset.Zero) }) {
                drawPath(deepSeekPath, color = Color(0xFF4D6BFE))
            }
        }
    }
}

private fun formatConnectionErrorMessage(error: Exception): String {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    return when {
        detail.contains("API Key 为空", ignoreCase = true) -> "API Key 为空，请点击「•••」配置私有密钥后重试。"
        detail.contains("401", ignoreCase = true) || detail.contains("403", ignoreCase = true) || detail.contains("API_KEY_INVALID", ignoreCase = true) ->
            "认证失败（401/403）：API Key 无效或未授权，请检查密钥。"
        detail.contains("404", ignoreCase = true) -> "服务端返回 404：模型未找到或接口地址错误。"
        detail.contains("429", ignoreCase = true) -> "请求过于频繁（429 限流）：请稍候重试。"
        detail.contains("Unable to resolve host", ignoreCase = true) || detail.contains("ConnectException", ignoreCase = true) ->
            "网络连接失败，请检查网络设置。"
        else -> "连接失败：" + detail.take(200)
    }
}

private fun apiConnectionFailureMessage(profile: ApiProfile, error: Exception): String {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    val functionMissing = detail.contains("HTTP 404", ignoreCase = true) &&
        detail.contains("Function", ignoreCase = true) &&
        detail.contains("Not found for account", ignoreCase = true)
    val authModeMismatch = detail.contains("API keys are not supported by this API", ignoreCase = true) ||
        detail.contains("Expected OAuth2 access token", ignoreCase = true)
    return when {
        functionMissing ->
            "服务端返回 404：模型列表可访问，但当前模型的推理函数/部署未找到。请改选该服务商中可调用的模型，或请服务商检查后端部署。详情：" + detail
        authModeMismatch && profile.type == "GEMINI" ->
            "Gemini API 使用官方 generativelanguage.googleapis.com/v1beta 地址，并通过 x-goog-api-key 发送密钥；AQ. 开头的新密钥也应走此方式。该 401 不能靠切换到 Cloud Translation 修复。请确认服务类型是「Gemini 大模型」、密钥从 Google AI Studio 完整复制且没有引号/空格；若仍失败，请在 AI Studio 新建密钥后重试。原始响应：" + detail
        authModeMismatch && profile.type == "GCLOUD" ->
            "当前配置是「Google 翻译 API」（Cloud Translation），不是 Gemini。若你的密钥以 AQ. 开头，它是 Gemini API 密钥，不能用于 Cloud Translation；请编辑此配置，将服务类型改为「Gemini 大模型」，并选择可用的 Gemini 模型。若确实要使用 Google 翻译 API，则需使用该服务支持的凭据。原始响应：" + detail
        authModeMismatch ->
            "Google 服务端拒绝了 API Key，当前请求地址很可能要求 OAuth2。请核对服务类型和请求地址，不要把 Vertex AI/Cloud Translation v3 地址当作 Gemini Developer API。原始响应：" + detail
        else ->
            "请求当前模型失败：" + detail + "。请检查服务地址、模型 ID、API Key 权限和供应商状态。"
    }
}

private fun typeLabel(t: String) = when (t) {
    "OPENAI" -> "OpenAI 兼容大模型"; "GEMINI" -> "Google Gemini 大模型"
    "GCLOUD" -> "Google Cloud Translation 官方翻译 API（需密钥）"
    else -> t
}

private data class ApiLogoLayer(val path: String, val color: Color)
private data class ApiLogoSpec(
    val layers: List<ApiLogoLayer> = emptyList(),
    val useGoogleTranslate: Boolean = false,
)

private fun apiLogoSpec(profile: ApiProfile): ApiLogoSpec {
    val providerName = profile.name.lowercase(Locale.ROOT)
    // Inspect only the hostname, not URL paths such as /openai/v1 used by compatible gateways.
    val providerHost = runCatching {
        Uri.parse(profile.baseUrl.trim()).host?.lowercase(Locale.ROOT).orEmpty()
    }.getOrDefault("")
    val genericCompatibleName = listOf(
        "openai compatible", "openai-compatible", "openai 兼容", "openai兼容",
        "兼容大模型", "通用大模型", "大模型接口"
    ).any { providerName.contains(it) }
    fun matches(vararg words: String) = words.any { word ->
        val key = word.lowercase(Locale.ROOT)
        providerHost.contains(key) || (!genericCompatibleName && providerName.contains(key))
    }
    val openAi = ApiLogoLayer(
        """M9.205 8.658v-2.26c0-.19.072-.333.238-.428l4.543-2.616c.619-.357 1.356-.523 2.117-.523 2.854 0 4.662 2.212 4.662 4.566 0 .167 0 .357-.024.547l-4.71-2.759a.797.797 0 00-.856 0l-5.97 3.473zm10.609 8.8V12.06c0-.333-.143-.57-.429-.737l-5.97-3.473 1.95-1.118a.433.433 0 01.476 0l4.543 2.617c1.309.76 2.189 2.378 2.189 3.948 0 1.808-1.07 3.473-2.76 4.163zM7.802 12.703l-1.95-1.142c-.167-.095-.239-.238-.239-.428V5.899c0-2.545 1.95-4.472 4.591-4.472 1 0 1.927.333 2.712.928L8.23 5.067c-.285.166-.428.404-.428.737v6.898zM12 15.128l-2.795-1.57v-3.33L12 8.658l2.795 1.57v3.33L12 15.128zm1.796 7.23c-1 0-1.927-.332-2.712-.927l4.686-2.712c.285-.166.428-.404.428-.737v-6.898l1.974 1.142c.167.095.238.238.238.428v5.233c0 2.545-1.974 4.472-4.614 4.472zm-5.637-5.303l-4.544-2.617c-1.308-.761-2.188-2.378-2.188-3.948A4.482 4.482 0 014.21 6.327v5.423c0 .333.143.571.428.738l5.947 3.449-1.95 1.118a.432.432 0 01-.476 0zm-.262 3.9c-2.688 0-4.662-2.021-4.662-4.519 0-.19.024-.38.047-.57l4.686 2.71c.286.167.571.167.856 0l5.97-3.448v2.26c0 .19-.07.333-.237.428l-4.543 2.616c-.619.357-1.356.523-2.117.523zm5.899 2.83a5.947 5.947 0 005.827-4.756C22.287 18.339 24 15.84 24 13.296c0-1.665-.713-3.282-1.998-4.448.119-.5.19-.999.19-1.498 0-3.401-2.759-5.947-5.946-5.947-.642 0-1.26.095-1.88.31A5.962 5.962 0 0010.205 0a5.947 5.947 0 00-5.827 4.757C1.713 5.447 0 7.945 0 10.49c0 1.666.713 3.283 1.998 4.448-.119.5-.19 1-.19 1.499 0 3.401 2.759 5.946 5.946 5.946.642 0 1.26-.095 1.88-.309a5.96 5.96 0 004.162 1.713z""",
        Color(0xFF111111),
    )
    val deepSeek = ApiLogoLayer(
        DEEPSEEK_LOGO_PATH,
        Color(0xFF4D6BFE),
    )
    val gemini = ApiLogoLayer(
        GEMINI_LOGO_PATH,
        Color(0xFF8E75B2),
    )
    val qwen = ApiLogoLayer(
        """M12.604 1.34c.393.69.784 1.382 1.174 2.075a.18.18 0 00.157.091h5.552c.174 0 .322.11.446.327l1.454 2.57c.19.337.24.478.024.837-.26.43-.513.864-.76 1.3l-.367.658c-.106.196-.223.28-.04.512l2.652 4.637c.172.301.111.494-.043.77-.437.785-.882 1.564-1.335 2.34-.159.272-.352.375-.68.37-.777-.016-1.552-.01-2.327.016a.099.099 0 00-.081.05 575.097 575.097 0 01-2.705 4.74c-.169.293-.38.363-.725.364-.997.003-2.002.004-3.017.002a.537.537 0 01-.465-.271l-1.335-2.323a.09.09 0 00-.083-.049H4.982c-.285.03-.553-.001-.805-.092l-1.603-2.77a.543.543 0 01-.002-.54l1.207-2.12a.198.198 0 000-.197 550.951 550.951 0 01-1.875-3.272l-.79-1.395c-.16-.31-.173-.496.095-.965.465-.813.927-1.625 1.387-2.436.132-.234.304-.334.584-.335a338.3 338.3 0 012.589-.001.124.124 0 00.107-.063l2.806-4.895a.488.488 0 01.422-.246c.524-.001 1.053 0 1.583-.006L11.704 1c.341-.003.724.032.9.34zm-3.432.403a.06.06 0 00-.052.03L6.254 6.788a.157.157 0 01-.135.078H3.253c-.056 0-.07.025-.041.074l5.81 10.156c.025.042.013.062-.034.063l-2.795.015a.218.218 0 00-.2.116l-1.32 2.31c-.044.078-.021.118.068.118l5.716.008c.046 0 .08.02.104.061l1.403 2.454c.046.081.092.082.139 0l5.006-8.76.783-1.382a.055.055 0 01.096 0l1.424 2.53a.122.122 0 00.107.062l2.763-.02a.04.04 0 00.035-.02.041.041 0 000-.04l-2.9-5.086a.108.108 0 010-.113l.293-.507 1.12-1.977c.024-.041.012-.062-.035-.062H9.2c-.059 0-.073-.026-.043-.077l1.434-2.505a.107.107 0 000-.114L9.225 1.774a.06.06 0 00-.053-.031zm6.29 8.02c.046 0 .058.02.034.06l-.832 1.465-2.613 4.585a.056.056 0 01-.05.029.058.058 0 01-.05-.029L8.498 9.841c-.02-.034-.01-.052.028-.054l.216-.012 6.722-.012z""",
        Color(0xFF5F5CE6),
    )
    val moonshot = ApiLogoLayer(
        """M1.052 16.916l9.539 2.552a21.007 21.007 0 00.06 2.033l5.956 1.593a11.997 11.997 0 01-5.586.865l-.18-.016-.044-.004-.084-.009-.094-.01a11.605 11.605 0 01-.157-.02l-.107-.014-.11-.016a11.962 11.962 0 01-.32-.051l-.042-.008-.075-.013-.107-.02-.07-.015-.093-.019-.075-.016-.095-.02-.097-.023-.094-.022-.068-.017-.088-.022-.09-.024-.095-.025-.082-.023-.109-.03-.062-.02-.084-.025-.093-.028-.105-.034-.058-.019-.08-.026-.09-.031-.066-.024a6.293 6.293 0 01-.044-.015l-.068-.025-.101-.037-.057-.022-.08-.03-.087-.035-.088-.035-.079-.032-.095-.04-.063-.028-.063-.027a5.655 5.655 0 01-.041-.018l-.066-.03-.103-.047-.052-.024-.096-.046-.062-.03-.084-.04-.086-.044-.093-.047-.052-.027-.103-.055-.057-.03-.058-.032a6.49 6.49 0 01-.046-.026l-.094-.053-.06-.034-.051-.03-.072-.041-.082-.05-.093-.056-.052-.032-.084-.053-.061-.039-.079-.05-.07-.047-.053-.035a7.785 7.785 0 01-.054-.036l-.044-.03-.044-.03a6.066 6.066 0 01-.04-.028l-.057-.04-.076-.054-.069-.05-.074-.054-.056-.042-.076-.057-.076-.059-.086-.067-.045-.035-.064-.052-.074-.06-.089-.073-.046-.039-.046-.039a7.516 7.516 0 01-.043-.037l-.045-.04-.061-.053-.07-.062-.068-.06-.062-.058-.067-.062-.053-.05-.088-.084a13.28 13.28 0 01-.099-.097l-.029-.028-.041-.042-.069-.07-.05-.051-.05-.053a6.457 6.457 0 01-.168-.179l-.08-.088-.062-.07-.071-.08-.042-.049-.053-.062-.058-.068-.046-.056a7.175 7.175 0 01-.027-.033l-.045-.055-.066-.082-.041-.052-.05-.064-.02-.025a11.99 11.99 0 01-1.44-2.402zm-1.02-5.794l11.353 3.037a20.468 20.468 0 00-.469 2.011l10.817 2.894a12.076 12.076 0 01-1.845 2.005L.657 15.923l-.016-.046-.035-.104a11.965 11.965 0 01-.05-.153l-.007-.023a11.896 11.896 0 01-.207-.741l-.03-.126-.018-.08-.021-.097-.018-.081-.018-.09-.017-.084-.018-.094c-.026-.141-.05-.283-.071-.426l-.017-.118-.011-.083-.013-.102a12.01 12.01 0 01-.019-.161l-.005-.047a12.12 12.12 0 01-.034-2.145zm1.593-5.15l11.948 3.196c-.368.605-.705 1.231-1.01 1.875l11.295 3.022c-.142.82-.368 1.612-.668 2.365l-11.55-3.09L.124 10.26l.015-.1.008-.049.01-.067.015-.087.018-.098c.026-.148.056-.295.088-.442l.028-.124.02-.085.024-.097c.022-.09.045-.18.07-.268l.028-.102.023-.083.03-.1.025-.082.03-.096.026-.082.031-.095a11.896 11.896 0 011.01-2.232zm4.442-4.4L17.352 4.59a20.77 20.77 0 00-1.688 1.721l7.823 2.093c.267.852.442 1.744.513 2.665L2.106 5.213l.045-.065.027-.04.04-.055.046-.065.055-.076.054-.072.064-.086.05-.065.057-.073.055-.07.06-.074.055-.069.065-.077.054-.066.066-.077.053-.06.072-.082.053-.06.067-.074.054-.058.073-.078.058-.06.063-.067.168-.17.1-.098.059-.056.076-.071a12.084 12.084 0 012.272-1.677zM12.017 0h.097l.082.001.069.001.054.002.068.002.046.001.076.003.047.002.06.003.054.002.087.005.105.007.144.011.088.007.044.004.077.008.082.008.047.005.102.012.05.006.108.014.081.01.042.006.065.01.207.032.07.012.065.011.14.026.092.018.11.022.046.01.075.016.041.01L14.7.3l.042.01.065.015.049.012.071.017.096.024.112.03.113.03.113.032.05.015.07.02.078.024.073.023.05.016.05.016.076.025.099.033.102.036.048.017.064.023.093.034.11.041.116.045.1.04.047.02.06.024.041.018.063.026.04.018.057.025.11.048.1.046.074.035.075.036.06.028.092.046.091.045.102.052.053.028.049.026.046.024.06.033.041.022.052.029.088.05.106.06.087.051.057.034.053.032.096.059.088.055.098.062.036.024.064.041.084.056.04.027.062.042.062.043.023.017c.054.037.108.075.161.114l.083.06.065.048.056.043.086.065.082.064.04.03.05.041.086.069.079.065.085.071c.712.6 1.353 1.283 1.909 2.031L7.222.994l.062-.027.065-.028.081-.034.086-.035c.113-.045.227-.09.341-.131l.096-.035.093-.033.084-.03.096-.031c.087-.03.176-.058.264-.085l.091-.027.086-.025.102-.03.085-.023.1-.026L9.04.37l.09-.023.091-.022.095-.022.09-.02.098-.021.091-.02.095-.018.092-.018.1-.018.091-.016.098-.017.092-.014.097-.015.092-.013.102-.013.091-.012.105-.012.09-.01.105-.01c.093-.01.186-.018.28-.024l.106-.008.09-.005.11-.006.093-.004.1-.004.097-.002.099-.002.197-.002z""",
        Color(0xFF171717),
    )
    val claude = ApiLogoLayer(
        """M4.709 15.955l4.72-2.647.08-.23-.08-.128H9.2l-.79-.048-2.698-.073-2.339-.097-2.266-.122-.571-.121L0 11.784l.055-.352.48-.321.686.06 1.52.103 2.278.158 1.652.097 2.449.255h.389l.055-.157-.134-.098-.103-.097-2.358-1.596-2.552-1.688-1.336-.972-.724-.491-.364-.462-.158-1.008.656-.722.881.06.225.061.893.686 1.908 1.476 2.491 1.833.365.304.145-.103.019-.073-.164-.274-1.355-2.446-1.446-2.49-.644-1.032-.17-.619a2.97 2.97 0 01-.104-.729L6.283.134 6.696 0l.996.134.42.364.62 1.414 1.002 2.229 1.555 3.03.456.898.243.832.091.255h.158V9.01l.128-1.706.237-2.095.23-2.695.08-.76.376-.91.747-.492.584.28.48.685-.067.444-.286 1.851-.559 2.903-.364 1.942h.212l.243-.242.985-1.306 1.652-2.064.73-.82.85-.904.547-.431h1.033l.76 1.129-.34 1.166-1.064 1.347-.881 1.142-1.264 1.7-.79 1.36.073.11.188-.02 2.856-.606 1.543-.28 1.841-.315.833.388.091.395-.328.807-1.969.486-2.309.462-3.439.813-.042.03.049.061 1.549.146.662.036h1.622l3.02.225.79.522.474.638-.079.485-1.215.62-1.64-.389-3.829-.91-1.312-.329h-.182v.11l1.093 1.068 2.006 1.81 2.509 2.33.127.578-.322.455-.34-.049-2.205-1.657-.851-.747-1.926-1.62h-.128v.17l.444.649 2.345 3.521.122 1.08-.17.353-.608.213-.668-.122-1.374-1.925-1.415-2.167-1.143-1.943-.14.08-.674 7.254-.316.37-.729.28-.607-.461-.322-.747.322-1.476.389-1.924.315-1.53.286-1.9.17-.632-.012-.042-.14.018-1.434 1.967-2.18 2.945-1.726 1.845-.414.164-.717-.37.067-.662.401-.589 2.388-3.036 1.44-1.882.93-1.086-.006-.158h-.055L4.132 18.56l-1.13.146-.487-.456.061-.746.231-.243 1.908-1.312-.006.006z""",
        Color(0xFFD97757),
    )
    val googleCloud = ApiLogoLayer(
        """M4.914 5.18c3.32-3.762 9.095-4.247 12.91-1.13l.362.312.252.232A9.4 9.4 0 0121.02 8.93 6.778 6.778 0 0124 14.597c-.028 3.744-3.087 6.726-6.83 6.697H6.739a6.746 6.746 0 01-3.869-1.222l-.224-.162-.302-.247a6.778 6.778 0 01.54-10.673l-.004.001a9.644 9.644 0 012.034-3.812zm10.345 2.11c-2.143-1.734-5.282-1.46-7.138.578l-.026.025a6.77 6.77 0 014.045 2.523l-3.023 3.023a2.606 2.606 0 10-2.379 3.682h10.43c1.44 0 2.607-1.137 2.607-2.576a2.607 2.607 0 00-2.606-2.607v-.52a5.205 5.205 0 00-1.685-3.933l-.225-.195z""",
        Color(0xFF4285F4),
    )
    val google = listOf(
        ApiLogoLayer(
            """M23 12.245c0-.905-.075-1.565-.236-2.25h-10.54v4.083h6.186c-.124 1.014-.797 2.542-2.294 3.569l-.021.136 3.332 2.53.23.022C21.779 18.417 23 15.593 23 12.245z""",
            Color(0xFF4285F4),
        ),
        ApiLogoLayer(
            """M12.225 23c3.03 0 5.574-.978 7.433-2.665l-3.542-2.688c-.948.648-2.22 1.1-3.891 1.1a6.745 6.745 0 01-6.386-4.572l-.132.011-3.465 2.628-.045.124C4.043 20.531 7.835 23 12.225 23z""",
            Color(0xFF34A853),
        ),
        ApiLogoLayer(
            """M5.84 14.175A6.65 6.65 0 015.463 12c0-.758.138-1.491.361-2.175l-.006-.147-3.508-2.67-.115.054A10.831 10.831 0 001 12c0 1.772.436 3.447 1.197 4.938l3.642-2.763z""",
            Color(0xFFFBBC05),
        ),
        ApiLogoLayer(
            """M12.225 5.253c2.108 0 3.529.892 4.34 1.638l3.167-3.031C17.787 2.088 15.255 1 12.225 1 7.834 1 4.043 3.469 2.197 7.062l3.63 2.763a6.77 6.77 0 016.398-4.572z""",
            Color(0xFFEA4335),
        ),
    )
    if (profile.type == "GCLOUD" || matches("google cloud", "googlecloud", "谷歌云")) {
        return ApiLogoSpec(listOf(googleCloud))
    }
    if (matches("google translate", "google 翻译", "谷歌翻译")) {
        return ApiLogoSpec(useGoogleTranslate = true)
    }

    return when {
        profile.type == "GEMINI" || matches("gemini") -> ApiLogoSpec(listOf(gemini))
        matches("deepseek", "深度求索") -> ApiLogoSpec(listOf(deepSeek))
        matches("qwen", "通义千问", "通义", "dashscope", "百炼") -> ApiLogoSpec(listOf(qwen))
        matches("kimi", "moonshot", "月之暗面") -> ApiLogoSpec(listOf(moonshot))
        matches("claude", "anthropic", "克劳德") -> ApiLogoSpec(listOf(claude))
        matches("google", "谷歌") -> ApiLogoSpec(google)
        matches("openai", "chatgpt", "gpt-") -> ApiLogoSpec(listOf(openAi))
        else -> ApiLogoSpec()
    }
}

/**
 * Resolve official brand domains even when the configured API base URL points at a gateway,
 * proxy or compatibility endpoint. Keep specific product hosts ahead of their parent domains.
 */
private fun preferredApiLogoHost(profile: ApiProfile): String? {
    val name = profile.name.lowercase(Locale.ROOT)
    val officialHost = when {
        profile.type == "GEMINI" || name.contains("gemini") -> "gemini.google.com"
        profile.type == "GCLOUD" || name.contains("google cloud") || name.contains("谷歌云") -> "cloud.google.com"
        name.contains("deepseek") || name.contains("深度求索") -> "deepseek.com"
        name.contains("openai") || name.contains("chatgpt") -> "openai.com"
        name.contains("anthropic") || name.contains("claude") -> "anthropic.com"
        name.contains("moonshot") || name.contains("kimi") || name.contains("月之暗面") -> "moonshot.cn"
        name.contains("qwen") || name.contains("通义千问") || name.contains("dashscope") || name.contains("百炼") -> "qwenlm.ai"
        name.contains("siliconflow") || name.contains("硅基流动") -> "siliconflow.cn"
        name.contains("zhipu") || name.contains("智谱") || name.contains("glm") -> "z.ai"
        name.contains("minimax") || name.contains("海螺") -> "minimaxi.com"
        name.contains("baichuan") || name.contains("百川") -> "baichuan-ai.com"
        name.contains("volcengine") || name.contains("火山引擎") || name.contains("豆包") -> "volcengine.com"
        else -> null
    }
    val fromConfiguredUrl = runCatching {
        Uri.parse(profile.baseUrl.trim()).host?.lowercase(Locale.ROOT)?.trimEnd('.')
    }.getOrNull()
    // If a known provider is identified by name, prefer its first-party domain over the
    // third-party API gateway. Otherwise inspect the API endpoint host and use its own branding.
    return (officialHost ?: fromConfiguredUrl)?.takeIf(::isPublicLogoLookupHost)
}

@Composable
private fun ApiProviderLogo(profile: ApiProfile, selected: Boolean, imageLoader: ImageLoader) {
    val context = LocalContext.current.applicationContext
    val spec = remember(profile.type, profile.name, profile.baseUrl) { apiLogoSpec(profile) }
    val host = remember(profile.type, profile.name, profile.baseUrl) {
        preferredApiLogoHost(profile)
    }
    val brandIds = remember(profile.type, profile.name, profile.baseUrl) {
        ProviderLogoResolver.brandIdsFor(profile.name, profile.baseUrl, profile.type)
    }
    // Gemini 优先用官方彩色 Logo；取不到时回退内置矢量图（不再退成通用图标）。
    val preferOnlineLogo = profile.type == "GEMINI" ||
        profile.name.contains("gemini", ignoreCase = true)
    val needsOnlineLogo = preferOnlineLogo || (spec.layers.isEmpty() && !spec.useGoogleTranslate)
    val downloadedLogo by produceState<File?>(null, host, needsOnlineLogo, brandIds) {
        value = null
        if (!needsOnlineLogo) return@produceState
        value = ProviderLogoResolver.resolve(context, host, brandIds)
    }

    Box(
        Modifier.size(52.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) Color(0xFFEAF1FF) else Color(0xFFF7F9FC)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            spec.useGoogleTranslate -> GoogleTranslateIcon(imageLoader)
            // 已规范化的 192×192 透明 PNG，已留好统一边距，直接 Fit 进 40dp 即可贴合卡片。
            downloadedLogo != null -> {
                SubcomposeAsyncImage(
                    model = downloadedLogo,
                    contentDescription = "${profile.name} 官方 Logo",
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                    imageLoader = imageLoader,
                    loading = { GenericApiLogo() },
                    error = { GenericApiLogo() },
                    success = { SubcomposeAsyncImageContent() },
                )
            }
            spec.layers.isNotEmpty() -> {
                val vectorPaths = remember(spec) {
                    spec.layers.map { layer ->
                        layer to PathParser().parsePathString(layer.path).toPath().apply {
                            fillType = PathFillType.EvenOdd
                        }
                    }
                }
                Canvas(Modifier.size(34.dp)) {
                    val scaleX = size.width / 24f
                    val scaleY = size.height / 24f
                    withTransform({ scale(scaleX, scaleY, pivot = Offset.Zero) }) {
                        vectorPaths.forEach { (layer, path) -> drawPath(path, color = layer.color) }
                    }
                }
            }
            else -> GenericApiLogo()
        }
    }
}



private fun isPublicLogoLookupHost(host: String): Boolean {
    if (host.length !in 4..253 || !host.contains('.')) return false
    if (host == "localhost" || host.endsWith(".localhost") ||
        host.endsWith(".local") || host.endsWith(".internal") ||
        host.endsWith(".test") || host.endsWith(".invalid") ||
        host.endsWith(".example")
    ) return false
    if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
    if (host.contains(':') || host.any { it.isWhitespace() }) return false
    return host.split('.').all { label ->
        label.isNotBlank() && label.length <= 63 &&
            label.firstOrNull()?.let { it.isLetterOrDigit() } == true &&
            label.lastOrNull()?.let { it.isLetterOrDigit() } == true &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}

@Composable
private fun GenericApiLogo() {
    Canvas(Modifier.size(32.dp)) {
        val ink = Color(0xFF718096)
        val stroke = size.minDimension * 0.055f
        drawRoundRect(
            color = ink,
            topLeft = Offset(size.width * 0.18f, size.height * 0.2f),
            size = Size(size.width * 0.64f, size.height * 0.58f),
            cornerRadius = CornerRadius(size.minDimension * 0.08f),
            style = Stroke(width = stroke),
        )
        drawLine(ink, Offset(size.width * 0.25f, size.height * 0.4f), Offset(size.width * 0.75f, size.height * 0.4f), stroke)
        drawLine(ink, Offset(size.width * 0.25f, size.height * 0.58f), Offset(size.width * 0.75f, size.height * 0.58f), stroke)
        drawCircle(ink, stroke * 1.45f, Offset(size.width * 0.31f, size.height * 0.82f))
        drawCircle(ink, stroke * 1.45f, Offset(size.width * 0.5f, size.height * 0.82f))
        drawCircle(ink, stroke * 1.45f, Offset(size.width * 0.69f, size.height * 0.82f))
    }
}

@Composable
private fun FreeModelConfigDialog(
    type: String, // "GEMINI" or "DEEPSEEK"
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }

    val isGemini = type == "GEMINI"
    val title = if (isGemini) "Google Gemini 配置" else "DeepSeek 配置"
    val defaultModel = if (isGemini) FreeModelDefaults.GEMINI_MODEL else FreeModelDefaults.DEEPSEEK_MODEL

    val initialEncKey = remember {
        prefs.getString(if (isGemini) "free_gemini_key" else "free_deepseek_key", "").orEmpty()
    }
    var apiKey by remember {
        mutableStateOf(if (initialEncKey.isNotBlank()) Crypto.decrypt(initialEncKey) else "")
    }
    var showApiKey by remember { mutableStateOf(false) }

    var baseUrl by remember {
        mutableStateOf(
            if (isGemini) FreeModelDefaults.GEMINI_BASE_URL
            else prefs.getString("free_deepseek_base_url", FreeModelDefaults.DEEPSEEK_BASE_URL) ?: FreeModelDefaults.DEEPSEEK_BASE_URL
        )
    }
    var model by remember {
        mutableStateOf(
            prefs.getString(
                if (isGemini) "free_gemini_model" else "free_deepseek_model",
                defaultModel
            ) ?: defaultModel
        )
    }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth(0.92f)
                .heightIn(max = (configuration.screenHeightDp * 0.85f).dp)
                .clip(RoundedCornerShape(20.dp)).background(SurfaceDark)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGemini) GeminiLogo(selected = true) else DeepSeekLogo(selected = true)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = OnBg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(OutlineSoft))

            // API Key
            Labeled("私有 API Key（可选，覆盖默认配置）") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showApiKey = !showApiKey }) {
                            Text(if (showApiKey) "隐藏" else "显示", color = SubText, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    placeholder = {
                        Text(if (isGemini) "填入 Google AI Studio API Key" else "填入 DeepSeek API Key")
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandBright,
                        unfocusedBorderColor = SurfaceHigh,
                        focusedTextColor = OnBg,
                        unfocusedTextColor = OnBg,
                    ),
                )
            }

            if (!isGemini) {
                Labeled("Base URL") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://api.deepseek.com") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandBright,
                            unfocusedBorderColor = SurfaceHigh,
                            focusedTextColor = OnBg,
                            unfocusedTextColor = OnBg,
                        ),
                    )
                }
            }

            // Model
            Labeled("模型 ID") {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(defaultModel) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandBright,
                        unfocusedBorderColor = SurfaceHigh,
                        focusedTextColor = OnBg,
                        unfocusedTextColor = OnBg,
                    ),
                )
            }

            // Quick model selection chips
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val candidateModels = if (isGemini) {
                    listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite")
                } else {
                    listOf("deepseek-chat", "deepseek-reasoner")
                }
                candidateModels.forEach { candidate ->
                    FilterChip(
                        selected = model == candidate,
                        onClick = { model = candidate },
                        label = { Text(candidate, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandBright.copy(alpha = 0.15f),
                            selectedLabelColor = BrandBright,
                        ),
                    )
                }
            }

            // Test connection result
            testResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("连接成功")) Color(0xFF25965B) else WarnYellow,
                )
            }

            // Test connection button
            OutlinedButton(
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch(Dispatchers.IO) {
                        val started = System.currentTimeMillis()
                        try {
                            val engine = if (isGemini) {
                                GeminiEngine(apiKey = apiKey.trim(), model = model.trim().ifEmpty { defaultModel }, baseUrl = FreeModelDefaults.GEMINI_BASE_URL)
                            } else {
                                OpenAiCompatEngine(baseUrl = baseUrl.trim().ifEmpty { FreeModelDefaults.DEEPSEEK_BASE_URL }, apiKey = apiKey.trim(), model = model.trim().ifEmpty { defaultModel })
                            }
                            engine.testConnection()
                            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
                            withContext(Dispatchers.Main) {
                                testResult = "连接成功 · 耗时 ${elapsed}ms"
                                testing = false
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            val msg = formatConnectionErrorMessage(e)
                            withContext(Dispatchers.Main) {
                                testResult = msg
                                testing = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !testing,
            ) {
                Text(if (testing) "测试中…" else "测试此配置连接")
            }

            Spacer(Modifier.height(4.dp))

            // Action buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        val editor = prefs.edit()
                        if (isGemini) {
                            editor.remove("free_gemini_key")
                            editor.remove("free_gemini_model")
                        } else {
                            editor.remove("free_deepseek_key")
                            editor.remove("free_deepseek_base_url")
                            editor.remove("free_deepseek_model")
                        }
                        editor.apply()
                        onSaved()
                        onDismiss()
                    },
                ) {
                    Text("恢复默认", color = SubText)
                }

                Spacer(Modifier.weight(1f))

                TextButton(onClick = onDismiss) {
                    Text("取消", color = SubText)
                }

                Button(
                    onClick = {
                        val editor = prefs.edit()
                        if (isGemini) {
                            if (apiKey.isNotBlank()) {
                                editor.putString("free_gemini_key", Crypto.encrypt(apiKey.trim()))
                            } else {
                                editor.remove("free_gemini_key")
                            }
                            editor.putString("free_gemini_model", model.trim().ifEmpty { defaultModel })
                        } else {
                            if (apiKey.isNotBlank()) {
                                editor.putString("free_deepseek_key", Crypto.encrypt(apiKey.trim()))
                            } else {
                                editor.remove("free_deepseek_key")
                            }
                            editor.putString("free_deepseek_base_url", baseUrl.trim().ifEmpty { FreeModelDefaults.DEEPSEEK_BASE_URL })
                            editor.putString("free_deepseek_model", model.trim().ifEmpty { defaultModel })
                        }
                        editor.apply()
                        onSaved()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBright),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("保存配置", color = Color.White)
                }
            }
        }
    }
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
    var testingConnection by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<String?>(null) }

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

        fun makeCurrentProfile(): ApiProfile = ApiProfile(
            id = initial?.id ?: 0,
            name = name.ifBlank { typeLabel(type) },
            type = type,
            baseUrl = baseUrl.trim(),
            apiKey = Crypto.encrypt(apiKey.trim()),
            model = model.trim(),
            region = region.trim(),
            enabled = true,
        )

        fun pullModelsNow() {
            pulling = true
            pullError = null
            connectionMessage = null
            val profile = makeCurrentProfile()
            scope.launch(Dispatchers.IO) {
                try {
                    val list = EngineFactory.fromProfile(profile).listModels()
                        ?: throw IllegalStateException("该类型不支持拉取模型")
                    withContext(Dispatchers.Main) {
                        models = list
                        pulling = false
                        if (list.isEmpty()) pullError = "服务端没有返回可用模型，可手动填写模型名称。"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        pullError = e.message ?: "拉取失败"
                        pulling = false
                    }
                }
            }
        }

        fun testConnectionNow() {
            testingConnection = true
            connectionMessage = null
            val profile = makeCurrentProfile()
            scope.launch(Dispatchers.IO) {
                val startedAt = System.nanoTime()
                try {
                    EngineFactory.fromProfile(profile).testConnection()
                    val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                    withContext(Dispatchers.Main) {
                        connectionMessage = "模型连接成功 · ${elapsedMs} ms" +
                            if (profile.model.isNotBlank()) " · ${profile.model}" else ""
                        testingConnection = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                    withContext(Dispatchers.Main) {
                        connectionMessage = "模型连接失败 · ${elapsedMs} ms · " +
                            apiConnectionFailureMessage(profile, e)
                        testingConnection = false
                    }
                }
            }
        }
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

            if (type == "GCLOUD" && apiKey.trim().startsWith("AQ.")) {
                Text(
                    "检测到 AQ. 开头的密钥：这是 Gemini API 密钥，不能用于「Google 翻译 API」。请将服务类型改为「Gemini 大模型」，再测试连接。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 模型选择与连接检查共用同一块配置区域，避免编辑页出现分散入口。
            Labeled(if (fixedEngine) "连接测试" else "翻译模型与连接") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!fixedEngine) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("填写模型名称") },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!fixedEngine) {
                            Button(
                                onClick = { pullModelsNow() },
                                enabled = !pulling && !testingConnection &&
                                    apiKey.isNotBlank() &&
                                    (!needsBaseUrl || baseUrl.isNotBlank()),
                                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Brand,
                                    contentColor = androidx.compose.ui.graphics.Color.White,
                                ),
                            ) { Text(if (pulling) "拉取中…" else "↓ 拉取模型") }
                        }
                        OutlinedButton(
                            onClick = { testConnectionNow() },
                            enabled = !pulling && !testingConnection &&
                                apiKey.isNotBlank() &&
                                (!needsBaseUrl || baseUrl.isNotBlank()) &&
                                (fixedEngine || model.isNotBlank()),
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBright),
                        ) { Text(if (testingConnection) "测试中…" else "测试连接") }
                    }
                    connectionMessage?.let { message ->
                        Text(
                            message,
                            color = if (message.startsWith("模型连接成功")) Color(0xFF21945A) else DangerRed,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!fixedEngine) {
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
