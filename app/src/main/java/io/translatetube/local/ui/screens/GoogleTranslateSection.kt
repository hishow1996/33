package io.translatetube.local.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import io.translatetube.local.translate.MlKitModelCoordinator
import io.translatetube.local.ui.theme.*
import coil.ImageLoader
import coil.decode.SvgDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private data class OfflineTranslationPack(val title: String, val source: String, val target: String, val badge: String)

@Composable
fun GoogleTranslateIcon(imageLoader: ImageLoader? = null) {
    val appContext = LocalContext.current.applicationContext
    // SVG 解码器必须显式注册；默认 Coil 加载器会把 raw SVG 当成未知格式，
    // 结果就是图标区域留白。API 管理页传入共享加载器，其他页面则创建独立加载器。
    val effectiveImageLoader = remember(appContext, imageLoader) {
        imageLoader ?: ImageLoader.Builder(appContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    DisposableEffect(effectiveImageLoader, imageLoader) {
        onDispose {
            if (imageLoader == null) effectiveImageLoader.shutdown()
        }
    }
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF8FAFF)),
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.SubcomposeAsyncImage(
            model = io.translatetube.local.R.raw.google_translate_logo,
            imageLoader = effectiveImageLoader,
            contentDescription = "Google 翻译官方矢量图标",
            modifier = Modifier.size(38.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            loading = { GoogleTranslateLogoFallback() },
            error = { GoogleTranslateLogoFallback() },
            success = { coil.compose.SubcomposeAsyncImageContent() },
        )
    }
}

@Composable
private fun GoogleTranslateLogoFallback() {
    // 即使资源损坏或 SVG 解码失败，也提供可辨认的翻译图标，不再出现空白区域。
    Box(Modifier.size(34.dp)) {
        Surface(
            modifier = Modifier.align(Alignment.CenterStart).size(width = 23.dp, height = 28.dp),
            color = Color(0xFF4285F4),
            shape = RoundedCornerShape(4.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("文", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).size(width = 19.dp, height = 24.dp),
            color = Color(0xFF34A853),
            shape = RoundedCornerShape(4.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun GoogleTranslateSection() {
    val scope = rememberCoroutineScope()
    val packs = remember {
        listOf(
            OfflineTranslationPack("英语 ↔ 中文", "en", "zh", "EN"),
            OfflineTranslationPack("日语 ↔ 中文", "ja", "zh", "日"),
            OfflineTranslationPack("韩语 ↔ 中文", "ko", "zh", "韩"),
            OfflineTranslationPack("法语 ↔ 中文", "fr", "zh", "FR"),
            OfflineTranslationPack("德语 ↔ 中文", "de", "zh", "DE"),
            OfflineTranslationPack("西班牙语 ↔ 中文", "es", "zh", "ES"),
            OfflineTranslationPack("俄语 ↔ 中文", "ru", "zh", "RU"),
        )
    }
    var states by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var downloadMessage by remember { mutableStateOf("") }

    suspend fun packIsReady(pack: OfflineTranslationPack): Boolean {
        val manager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
        val sourceReady = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(pack.source)
            ?.let { manager.isModelDownloaded(com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(it).build()).await() }
            ?: false
        val targetReady = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(pack.target)
            ?.let { manager.isModelDownloaded(com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(it).build()).await() }
            ?: false
        return sourceReady && targetReady
    }

    LaunchedEffect(Unit) {
        try {
            states = withContext(Dispatchers.IO) {
                packs.associate { pack -> pack.source to if (packIsReady(pack)) "ready" else "missing" }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            states = packs.associate { it.source to "unknown" }
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("离线语言包", color = OnBg, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            val readyCount = states.values.count { it == "ready" }
            Text("${readyCount}/7", color = BrandBright, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "语言包需手动下载；已下载的语言可在无网络时使用。",
            color = SubText,
            style = MaterialTheme.typography.bodySmall,
        )

        packs.forEachIndexed { index, pack ->
            val state = states[pack.source] ?: "checking"
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(SurfaceDark),
                    contentAlignment = Alignment.Center) {
                    Text(pack.badge, color = BrandBright, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pack.title, color = OnBg, style = MaterialTheme.typography.bodyLarge)
                    when (state) {
                        "ready" -> Text("已下载", color = BrandBright, style = MaterialTheme.typography.labelMedium)
                        "downloading" -> Text("正在下载…", color = BrandBright, style = MaterialTheme.typography.labelMedium)
                        "failed" -> Text("下载失败", color = DangerRed, style = MaterialTheme.typography.labelMedium)
                        "checking" -> Text("检查中…", color = SubText, style = MaterialTheme.typography.labelMedium)
                    }
                }
                when (state) {
                    "ready" -> Text("✓", color = BrandBright, style = MaterialTheme.typography.titleLarge)
                    "downloading", "checking" -> CircularProgressIndicator(
                        modifier = Modifier.size(23.dp), strokeWidth = 2.5.dp, color = BrandBright)
                    else -> TextButton(
                        enabled = downloading == null,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        onClick = {
                            if (state == "ready" || downloading != null) return@TextButton
                            downloading = pack.source
                            downloadMessage = ""
                            states = states + (pack.source to "downloading")
                            scope.launch {
                                try {
                                    MlKitModelCoordinator.downloadLanguage(pack.source)
                                    if (pack.target != pack.source) MlKitModelCoordinator.downloadLanguage(pack.target)
                                    if (!packIsReady(pack)) throw IllegalStateException("语言包安装校验未通过")
                                    states = states + (pack.source to "ready")
                                    downloadMessage = ""
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    // Cancellation is lifecycle/navigation control flow, not a failed download.
                                    states = states + (pack.source to "missing")
                                    throw cancelled
                                } catch (e: Exception) {
                                    states = states + (pack.source to "failed")
                                    downloadMessage = "${pack.title}下载失败：${e.message ?: "请检查网络后重试"}"
                                } finally {
                                    downloading = null
                                }
                            }
                        }) { Text(if (state == "failed") "重试" else "下载", color = BrandBright) }
                }
            }
            if (index < packs.lastIndex) HorizontalDivider(color = OutlineSoft, thickness = 1.dp)
        }
        if (downloadMessage.isNotBlank()) Text(downloadMessage, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
    }
}
