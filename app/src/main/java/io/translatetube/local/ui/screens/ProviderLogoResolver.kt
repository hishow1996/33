package io.translatetube.local.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * API 卡片「官方 Logo」解析器。
 *
 * 流程：
 * 1. 并行收集候选图：品牌图标库（知名 AI 服务商）→ 官网 HTML 声明的图标 → 官网常见图标路径 → 公共 favicon 服务；
 * 2. 全部候选并行下载（受信号量限流），按优先级挑选第一张「质量合格」的图；
 * 3. 每张图都会解码并规范化为 [OUTPUT_PX] 见方的透明 PNG：自动裁掉空白边距、去掉纯色底、
 *    等比居中缩放并留出统一边距，因此任意比例/清晰度的原图都能贴合卡片 Logo 位；
 * 4. 结果写入本地缓存；失败会在内存里短暂记忆，避免滚动列表时反复请求。
 *
 * 全程在应用级协程作用域里执行：界面滚出屏幕不会中断下载，下次进入直接命中缓存。
 */
internal object ProviderLogoResolver {

    private const val CACHE_DIR = "provider-logo-cache-v3"
    private const val LEGACY_CACHE_DIR = "provider-logo-cache"
    private const val OUTPUT_PX = 192
    private const val CONTENT_FRACTION = 0.84f
    private const val BG_TOLERANCE = 24
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    private const val MAX_HTML_BYTES = 768 * 1024
    private const val FAILURE_RETRY_MS = 3 * 60 * 1000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    private const val HTML_ACCEPT = "text/html,application/xhtml+xml"

    private class Candidate(val url: String, val rank: Int, val minSide: Int)

    private class Prepared(
        val bitmap: Bitmap,
        val contentMinSide: Int,
        val vector: Boolean,
        val wide: Boolean,
    ) {
        /** 矢量图，或内容短边不小于 48px 且不是横幅/长条图，才算清晰合格。 */
        val good: Boolean get() = (vector || contentMinSide >= 48) && !wide
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val inFlight = HashMap<String, Deferred<File?>>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val downloadGate = Semaphore(6)

    @Volatile
    private var svgImageLoader: ImageLoader? = null

    @Volatile
    private var legacyCacheCleaned = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ---------------------------------------------------------------- 对外接口

    /**
     * 返回已规范化的本地 PNG；没有可用 Logo 时返回 null。
     * [host] 是服务商官方域名（可为空），[brandIds] 是品牌图标库里的 id（可为空）。
     */
    suspend fun resolve(context: Context, host: String?, brandIds: List<String>): File? {
        val appContext = context.applicationContext
        val key = cacheKey(host, brandIds) ?: return null

        if (!legacyCacheCleaned) {
            legacyCacheCleaned = true
            withContext(Dispatchers.IO) {
                runCatching { File(appContext.filesDir, LEGACY_CACHE_DIR).deleteRecursively() }
            }
        }

        val cached = withContext(Dispatchers.IO) { cachedLogo(appContext, key) }
        if (cached != null) return cached

        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < FAILURE_RETRY_MS) return null

        val job = synchronized(lock) {
            inFlight[key] ?: scope.async {
                try {
                    val file = discover(appContext, host, brandIds, key)
                    if (file == null) failedAt[key] = System.currentTimeMillis() else failedAt.remove(key)
                    file
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    failedAt[key] = System.currentTimeMillis()
                    null
                } finally {
                    synchronized(lock) { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return job.await()
    }

    /**
     * 根据名称 / Base URL / 类型推断品牌图标库里的 id。
     * Base URL 的域名比名称更可靠（名称可能只是「OpenAI 兼容」之类的通用叫法），所以域名命中的排在前面。
     */
    fun brandIdsFor(name: String, baseUrl: String, type: String): List<String> {
        val host = runCatching { URI(baseUrl.trim()).host }.getOrNull().orEmpty().lowercase(Locale.ROOT)
        val label = name.lowercase(Locale.ROOT)
        val ids = LinkedHashSet<String>()
        if (type == "GEMINI") ids.add("gemini")
        for ((keywords, brandIds) in brandTable) {
            if (keywords.any { host.contains(it) }) ids.addAll(brandIds)
        }
        for ((keywords, brandIds) in brandTable) {
            if (keywords.any { label.contains(it) }) ids.addAll(brandIds)
        }
        return ids.toList()
    }

    /** 关键词 → 品牌图标库（lobehub/lobe-icons）里的图标 id，靠前的 id 优先。 */
    private val brandTable: List<Pair<List<String>, List<String>>> = listOf(
        listOf("gemini", "generativelanguage") to listOf("gemini"),
        listOf("deepseek", "深度求索") to listOf("deepseek"),
        listOf("openai", "chatgpt") to listOf("openai"),
        listOf("anthropic", "claude") to listOf("claude", "anthropic"),
        listOf("moonshot", "kimi", "月之暗面") to listOf("kimi", "moonshot"),
        listOf("qwen", "通义", "dashscope", "aliyuncs", "百炼") to listOf("qwen", "alibabacloud"),
        listOf("siliconflow", "硅基") to listOf("siliconcloud"),
        listOf("zhipu", "智谱", "bigmodel", "glm") to listOf("zhipu", "zai"),
        listOf("minimax", "海螺") to listOf("minimax"),
        listOf("baichuan", "百川") to listOf("baichuan"),
        listOf("volcengine", "volces", "火山", "豆包", "doubao") to listOf("doubao", "volcengine"),
        listOf("stepfun", "阶跃") to listOf("stepfun"),
        listOf("hunyuan", "混元") to listOf("hunyuan"),
        listOf("xf-yun", "讯飞", "星火") to listOf("spark"),
        listOf("qianfan", "wenxin", "文心", "千帆") to listOf("wenxin", "baidu"),
        listOf("01.ai", "lingyiwanwu", "零一") to listOf("yi"),
        listOf("sensenova", "商汤") to listOf("sensenova"),
        listOf("openrouter") to listOf("openrouter"),
        listOf("groq") to listOf("groq"),
        listOf("mistral") to listOf("mistral"),
        listOf("x.ai", "grok") to listOf("xai", "grok"),
        listOf("perplexity") to listOf("perplexity"),
        listOf("together") to listOf("together"),
        listOf("cohere") to listOf("cohere"),
        listOf("ollama") to listOf("ollama"),
        listOf("fireworks") to listOf("fireworks"),
        listOf("novita") to listOf("novita"),
        listOf("nvidia") to listOf("nvidia"),
        listOf("huggingface") to listOf("huggingface"),
        listOf("cloudflare") to listOf("cloudflare"),
        listOf("azure") to listOf("azure"),
        listOf("github") to listOf("github"),
    )

    // ---------------------------------------------------------------- 候选收集

    private suspend fun discover(
        context: Context,
        host: String?,
        brandIds: List<String>,
        key: String,
    ): File? = coroutineScope {
        val siteHosts = if (host == null) emptyList() else candidateWebsiteHosts(host)
        val jobs = ArrayList<Deferred<Pair<Candidate, Prepared?>>>()
        val seen = HashSet<String>()

        fun launchCandidate(url: String, rank: Int, minSide: Int) {
            if (!seen.add(url)) return
            val candidate = Candidate(url, rank, minSide)
            jobs.add(async { candidate to fetchPrepared(context, candidate) })
        }

        // 官网首页与固定候选并行下载，不互相等待。
        val homepages = siteHosts.map { siteHost ->
            async { fetchHomepage("https://$siteHost/") }
        }

        // 知名服务商：品牌图标库优先（透明底、构图统一，最适合做卡片头像；npmmirror 国内可直连）。
        var brandRank = 0
        for (id in brandIds.take(2)) {
            for (url in brandPackUrls(id)) launchCandidate(url, brandRank++, 24)
        }

        // 官网常见图标路径。
        var pathRank = 300
        for (siteHost in siteHosts) {
            launchCandidate("https://$siteHost/apple-touch-icon.png", pathRank++, 48)
            launchCandidate("https://$siteHost/favicon.svg", pathRank++, 0)
            launchCandidate("https://$siteHost/favicon.ico", pathRank++, 16)
        }

        // 公共 favicon 服务：只作兜底，且要求至少 32px，避免拿到占位小图。
        var serviceRank = 400
        for (siteHost in siteHosts) {
            launchCandidate("https://www.google.com/s2/favicons?domain=$siteHost&sz=128", serviceRank++, 32)
            launchCandidate("https://icons.duckduckgo.com/ip3/$siteHost.ico", serviceRank++, 32)
        }

        // 官网 HTML 里声明的图标（apple-touch-icon / svg / 大尺寸 icon / logo）。
        var declaredRank = 100
        for (page in homepages.awaitAll()) {
            val html = page ?: continue
            for (url in extractDeclaredLogoUrls(html.first, html.second).take(5)) {
                launchCandidate(url, declaredRank++, 16)
            }
        }

        val prepared = jobs.awaitAll()
            .mapNotNull { (candidate, result) -> result?.let { candidate to it } }
            .sortedBy { it.first.rank }
        val best = prepared.firstOrNull { it.second.good } ?: prepared.firstOrNull()
        best?.let { writeCache(context, key, it.second.bitmap) }
    }

    private fun brandPackUrls(id: String): List<String> = listOf(
        "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/light/${id}-color.png",
        "https://unpkg.com/@lobehub/icons-static-png@latest/light/${id}-color.png",
        "https://registry.npmmirror.com/@lobehub/icons-static-svg/latest/files/icons/${id}-color.svg",
        "https://registry.npmmirror.com/@lobehub/icons-static-png/latest/files/light/${id}.png",
        "https://registry.npmmirror.com/@lobehub/icons-static-svg/latest/files/icons/${id}.svg",
    )

    private suspend fun fetchHomepage(url: String): Pair<String, String>? = try {
        downloadGate.withPermit {
            withContext(Dispatchers.IO) {
                httpGet(url, HTML_ACCEPT, MAX_HTML_BYTES)?.let { (bytes, finalUrl) ->
                    bytes.toString(Charsets.UTF_8) to finalUrl
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun fetchPrepared(context: Context, candidate: Candidate): Prepared? {
        return try {
            val bytes = downloadGate.withPermit {
                withContext(Dispatchers.IO) { httpGet(candidate.url, IMAGE_ACCEPT, MAX_IMAGE_BYTES)?.first }
            } ?: return null
            val prepared = withContext(Dispatchers.Default) { decodeAndNormalize(context, bytes) } ?: return null
            if (prepared.vector || prepared.contentMinSide >= candidate.minSide) prepared else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- HTML 解析

    /** 从官网 HTML 提取声明的图标，按「适合做头像」的程度排序：svg > apple-touch-icon > 大 icon > logo > 小 icon > 分享图。 */
    private fun extractDeclaredLogoUrls(html: String, pageUrl: String): List<String> {
        val scored = ArrayList<Pair<String, Int>>()

        fun add(raw: String?, score: Int) {
            val value = raw?.trim()?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")
                ?: return
            if (value.isBlank() || value.startsWith("data:", true) || value.startsWith("javascript:", true)) return
            val resolved = runCatching { URI(pageUrl).resolve(value).normalize() }.getOrNull() ?: return
            if (resolved.scheme != "https") return
            val resolvedHost = resolved.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return
            if (!isPublicHost(resolvedHost)) return
            scored.add(resolved.toASCIIString() to score)
        }

        val tagRegex = Regex("""(?is)<(link|meta)\b[^>]*>""")
        val attrRegex = Regex("""(?is)([a-zA-Z_:][a-zA-Z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        val sizeRegex = Regex("""(\d+)\s*[xX]\s*(\d+)""")

        for (match in tagRegex.findAll(html)) {
            val tag = match.groupValues[1].lowercase(Locale.ROOT)
            val attrs = HashMap<String, String>()
            for (attr in attrRegex.findAll(match.value)) {
                val name = attr.groupValues[1].lowercase(Locale.ROOT)
                val value = attr.groupValues[2].ifBlank { attr.groupValues[3].ifBlank { attr.groupValues[4] } }
                attrs[name] = value
            }
            if (tag == "link") {
                val rel = attrs["rel"].orEmpty().lowercase(Locale.ROOT)
                val href = attrs["href"]
                val path = href?.substringBefore('?').orEmpty()
                val edge = sizeRegex.find(attrs["sizes"].orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val isSvg = path.endsWith(".svg", ignoreCase = true) ||
                    attrs["type"].orEmpty().contains("svg", ignoreCase = true)
                when {
                    rel.contains("mask-icon") -> Unit
                    rel.contains("apple-touch-icon") ->
                        add(href, 600 + minOf(if (edge > 0) edge else 120, 512) / 4)
                    rel.contains("logo") -> add(href, 450)
                    rel.contains("icon") -> add(
                        href,
                        when {
                            isSvg -> 900
                            edge >= 96 -> 500 + minOf(edge, 512) / 4
                            edge > 0 -> 250 + edge / 4
                            path.endsWith(".ico", ignoreCase = true) -> 200
                            else -> 300
                        },
                    )
                }
            } else {
                val metaKey = (attrs["property"].orEmpty() + " " + attrs["name"].orEmpty() +
                    " " + attrs["itemprop"].orEmpty()).lowercase(Locale.ROOT)
                val content = attrs["content"]
                when {
                    metaKey.contains("logo") -> add(content, 440)
                    metaKey.contains("og:image") || metaKey.contains("twitter:image") -> add(content, 100)
                }
            }
        }

        // Schema.org JSON-LD 常常直接给出官方品牌 logo。
        val jsonLdLogo = Regex(
            """(?is)["']logo["']\s*:\s*(?:["']([^"']+)["']|\{[^{}]*?["']url["']\s*:\s*["']([^"']+)["'])"""
        )
        for (m in jsonLdLogo.findAll(html)) {
            add(m.groupValues[1].ifBlank { m.groupValues[2] }, 430)
        }

        return scored.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    // ---------------------------------------------------------------- 解码 + 规范化

    private suspend fun decodeAndNormalize(context: Context, bytes: ByteArray): Prepared? {
        if (bytes.isEmpty()) return null
        val prefix = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (prefix.startsWith("<")) {
            // 网页/错误页不是图片；只有 <svg> 才继续。
            if (prefix.contains("<html", ignoreCase = true) ||
                prefix.contains("<!doctype html", ignoreCase = true)
            ) return null
            val text = bytes.toString(Charsets.UTF_8)
            val start = text.indexOf("<svg", ignoreCase = true)
            if (start < 0 || !text.contains("</svg", ignoreCase = true)) return null
            val bitmap = rasterizeSvg(context, text.substring(start)) ?: return null
            return normalize(bitmap, vector = true)
        }
        val bitmap = decodeRaster(bytes) ?: return null
        return normalize(bitmap, vector = false)
    }

    private fun svgLoader(context: Context): ImageLoader =
        svgImageLoader ?: synchronized(this) {
            svgImageLoader ?: ImageLoader.Builder(context)
                .components { add(SvgDecoder.Factory()) }
                .build()
                .also { svgImageLoader = it }
        }

    /** SVG 先栅格化为位图：渲染失败/空白的 SVG 会在这里被淘汰，而不是等到界面才显示成空白。 */
    private suspend fun rasterizeSvg(context: Context, svg: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(ByteBuffer.wrap(svg.toByteArray(Charsets.UTF_8)))
            .size(512, 512)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = svgLoader(context).execute(request)
        val drawable = (result as? SuccessResult)?.drawable ?: return null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null) return bitmap
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(out))
        return out
    }

    /** png / jpg / webp / gif / ico 统一交给系统解码；解不出来的一律视为无效候选。 */
    private fun decodeRaster(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > 8192 || bounds.outHeight > 8192
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * 把任意原图变成 [OUTPUT_PX] 见方的 PNG 位图，让它刚好适配卡片 Logo 位：
     * - 透明底 / 近白纯色底：裁掉空白边距，去掉白底，等比居中并统一留边；
     * - 品牌色纯色底（典型的 apple-touch-icon）：保留底色，铺满整格，由界面再裁圆角；
     * - 其它不规则整图：等比缩放居中。
     * 全空白、纯白（深色模式白 Logo）的图返回 null。
     */
    private fun normalize(src: Bitmap, vector: Boolean): Prepared? {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return null
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[w * h - 1])
        var seeThrough = 0
        for (p in px) if ((p ushr 24) < 16) seeThrough++
        val hasTransparency = seeThrough * 50L > px.size || corners.any { (it ushr 24) < 16 }
        val bg = corners[0]
        val uniformBg = !hasTransparency && corners.all { colorDistance(it, bg) <= BG_TOLERANCE }
        val lightBg = uniformBg && luminance(bg) >= 232
        val cropMode = hasTransparency || lightBg

        var left = w
        var top = h
        var right = -1
        var bottom = -1
        var ink = 0
        var inkLuminance = 0L
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                val isInk = when {
                    hasTransparency -> (p ushr 24) > 24
                    uniformBg -> colorDistance(p, bg) > BG_TOLERANCE
                    else -> true
                }
                if (isInk) {
                    ink++
                    if (hasTransparency) inkLuminance += luminance(p)
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        // 几乎没有墨迹：空白图或占位图。
        if (right < left || bottom < top || ink.toLong() * 2000L < px.size) return null
        // 透明底的纯白 Logo（深色模式专用图）放在浅色卡片上会看不见。
        if (hasTransparency && inkLuminance / ink > 238L) return null

        val cropped: Bitmap
        val cw: Int
        val ch: Int
        if (cropMode) {
            cw = right - left + 1
            ch = bottom - top + 1
            if (lightBg) {
                val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                val line = IntArray(cw)
                for (y in 0 until ch) {
                    val row = (top + y) * w + left
                    for (x in 0 until cw) {
                        val p = px[row + x]
                        line[x] = if (colorDistance(p, bg) <= BG_TOLERANCE) 0 else (p or (0xFF shl 24))
                    }
                    bmp.setPixels(line, 0, cw, 0, y, cw, 1)
                }
                cropped = bmp
            } else {
                cropped = Bitmap.createBitmap(src, left, top, cw, ch)
            }
        } else {
            cw = w
            ch = h
            cropped = src
        }

        val out = Bitmap.createBitmap(OUTPUT_PX, OUTPUT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (!cropMode && uniformBg) canvas.drawColor(bg or (0xFF shl 24))
        val box = if (cropMode) OUTPUT_PX * CONTENT_FRACTION else OUTPUT_PX.toFloat()
        val scale = minOf(box / cw, box / ch)
        val tw = maxOf(1, (cw * scale).roundToInt())
        val th = maxOf(1, (ch * scale).roundToInt())
        val scaled = scaleSmooth(cropped, tw, th)
        canvas.drawBitmap(scaled, (OUTPUT_PX - tw) / 2f, (OUTPUT_PX - th) / 2f, paint)

        val shortSide = minOf(cw, ch)
        val wide = maxOf(cw, ch).toFloat() / shortSide.toFloat() > 2.4f
        return Prepared(out, shortSide, vector, wide)
    }

    /** 大图缩小时逐级减半，避免一次性缩小产生锯齿。 */
    private fun scaleSmooth(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var current = src
        while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        return if (current.width == targetW && current.height == targetH) {
            current
        } else {
            Bitmap.createScaledBitmap(current, targetW, targetH, true)
        }
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return maxOf(dr, dg, db)
    }

    private fun luminance(color: Int): Int =
        (0.299 * ((color shr 16) and 0xFF) + 0.587 * ((color shr 8) and 0xFF) + 0.114 * (color and 0xFF)).toInt()

    // ---------------------------------------------------------------- 网络 / 缓存

    private fun httpGet(url: String, accept: String, maxBytes: Int): Pair<ByteArray, String>? {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val finalUrl = response.request.url
                if (finalUrl.scheme != "https" || !isPublicHost(finalUrl.host)) return null
                val body = response.body ?: return null
                if (body.contentLength() > maxBytes) return null
                readBounded(body.byteStream(), maxBytes)?.let { it to finalUrl.toString() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return null
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun cacheKey(host: String?, brandIds: List<String>): String? {
        val raw = host?.takeIf { it.isNotBlank() }
            ?: brandIds.firstOrNull()?.let { "brand:$it" }
            ?: return null
        return sha256(raw.lowercase(Locale.ROOT))
    }

    private fun cachedLogo(context: Context, key: String): File? {
        val file = File(File(context.filesDir, CACHE_DIR), "$key.png")
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun writeCache(context: Context, key: String, bitmap: Bitmap): File? {
        val folder = File(context.filesDir, CACHE_DIR)
        if (!folder.exists() && !folder.mkdirs()) return null
        val target = File(folder, "$key.png")
        val temp = File(folder, "$key.png.part")
        return try {
            // 先写临时文件再改名，下载/写入中断不会留下损坏的缓存。
            val written = temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written) {
                temp.delete()
                return null
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target.takeIf { it.isFile && it.length() > 0L }
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- 域名工具

    /** 只允许公网域名：拒绝 IP、localhost、内网后缀，避免用户填的 Base URL 触发内网请求。 */
    fun isPublicHost(host: String): Boolean {
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

    /** 先试子域名（如 gemini.google.com），再试注册域名（google.com）。 */
    private fun candidateWebsiteHosts(host: String): List<String> {
        val normalized = host.lowercase(Locale.ROOT).removePrefix("www.")
        val parts = normalized.split('.')
        val twoLevelSuffixes = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au",
            "co.jp", "ne.jp", "or.jp", "com.cn", "net.cn", "org.cn", "com.sg",
            "com.hk", "com.tw", "co.in", "firm.in", "com.br", "com.mx", "co.nz"
        )
        val suffix = parts.takeLast(2).joinToString(".")
        val rootLabels = if (suffix in twoLevelSuffixes) 3 else 2
        val root = parts.takeLast(rootLabels.coerceAtMost(parts.size)).joinToString(".")
        return listOf(normalized, root).distinct().filter { isPublicHost(it) }
    }
}
