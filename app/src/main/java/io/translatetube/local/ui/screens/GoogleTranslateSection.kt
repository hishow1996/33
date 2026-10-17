package io.translatetube.local.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
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

private const val GOOGLE_TRANSLATE_LOGO_PATH = """M22.401 4.818h-9.927L10.927 0H1.599C.72 0 .002.719.002 1.599v16.275c0 .878.72 1.597 1.597 1.597h10L13.072 24H22.4c.878 0 1.597-.707 1.597-1.572V6.39c0-.865-.72-1.572-1.597-1.572zm-15.66 8.68c-2.07 0-3.75-1.68-3.75-3.75 0-2.07 1.68-3.75 3.75-3.75 1.012 0 1.86.375 2.512.976l-.99.952a2.194 2.194 0 0 0-1.522-.584c-1.305 0-2.363 1.08-2.363 2.409S5.436 12.16 6.74 12.16c1.507 0 2.13-1.08 2.19-1.808l-2.188-.002V9.066h3.51c.05.23.09.457.09.764 0 2.147-1.434 3.669-3.602 3.669zm16.757 8.93c0 .59-.492 1.072-1.097 1.072h-8.875l3.649-4.03h.005l-.74-2.302.006-.005s.568-.488 1.277-1.24c.712.771 1.63 1.699 2.818 2.805l.771-.772c-1.272-1.154-2.204-2.07-2.89-2.805.919-1.087 1.852-2.455 2.049-3.707h2.034v.002h.002v-.94h-4.532v-1.52h-1.471v1.52H14.3l-1.672-5.21.006.022h9.767c.605 0 1.097.48 1.097 1.072v16.038zm-6.484-7.311c-.536.548-.943.873-.943.873l-.008.004-1.46-4.548h4.764c-.307 1.084-.988 2.108-1.651 2.904-1.176-1.392-1.18-1.844-1.18-1.844h-1.222s.05.678 1.7 2.61z"""

@Composable
private fun GoogleTranslateLogoFallback() {
    // 使用与本地 SVG 相同的官方图形路径；图片资源解码失败时仍能准确显示，而不是通用占位图。
    val path = remember {
        PathParser().parsePathString(GOOGLE_TRANSLATE_LOGO_PATH).toPath()
    }
    Canvas(Modifier.size(34.dp)) {
        withTransform({
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero)
        }) {
            drawPath(path, color = Color(0xFF4285F4))
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
