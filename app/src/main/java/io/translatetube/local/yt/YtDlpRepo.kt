package io.translatetube.local.yt

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * yt-dlp 通用站点提取封装：B站 / TikTok / Twitter 等上千个网站的视频地址解析。
 *
 * 设计要点：
 * - 主入口 YouTube 优先走 NewPipeExtractor（快、稳）；本类兜所有网页源，
 *   YouTube 风控期也可从「网页视频」入口经本类自救；
 * - 只做「解析出可播放的媒体直链」，后续音频提取/转写/配音完全复用现有管线；
 * - 只挑「音视频合一」的可播格式（combined mp4 / HLS m3u8），
 *   分离流（video-only）在播放器侧无法直接合流，故不采用；
 * - 解析出的 URL 带站点签名会过期，调用方必须立即播放，不做长期缓存；
 * - 首次调用会解包 yt-dlp 运行时（约 2~5 秒，仅一次）；
 * - 解析请求带 --no-playlist（合集页取单视频）、--socket-timeout（防挂死），
 *   并透传 WebView Cookie（登录态）提高需登录站点成功率。
 */
object YtDlpRepo {

    private const val TAG = "YtDlpRepo"

    @Volatile private var inited = false

    /** 懒初始化（线程安全）。 */
    suspend fun ensureReady(ctx: Context): Unit = withContext(Dispatchers.IO) {
        if (inited) return@withContext
        synchronized(this) {
            if (inited) return@synchronized
            YoutubeDL.init(ctx.applicationContext)
            inited = true
            Log.i(TAG, "yt-dlp runtime ready, version=${runCatching { YoutubeDL.version(ctx.applicationContext) }.getOrNull()}")
        }
    }

    fun isReady(): Boolean = inited

    // ---- 会话级来源标记：resolve 成功后记录 mediaUrl → 源页面/Cookie 映射 ----
    // 最近 8 条（LRU）：用户在多个视频间往返切换时，旧会话的转写仍能走 yt-dlp 直下。
    // 进程重建后丢失（claim 返回 null），调用方自动回落现有流式管线；
    // 标记只存内存，Cookie 不持久化，与工程「下载凭据不落盘」约定一致。

    data class ClaimedOrigin(val pageUrl: String, val cookie: String?)

    private val resolvedOrigins = object : LinkedHashMap<String, ClaimedOrigin>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClaimedOrigin>): Boolean = size > 8
    }

    /** 若 mediaUrl 来自本进程内最近几次 yt-dlp 解析，返回其源页面地址与 Cookie。 */
    fun claimForMediaUrl(mediaUrl: String): ClaimedOrigin? =
        synchronized(resolvedOrigins) { resolvedOrigins[mediaUrl] }

    /**
     * 用 yt-dlp 直接下载网页视频的纯音频（bestaudio），供 Whisper 转写。
     * 不转码不合并（bestaudio 单流），文件由调用方放入 ASR 音频缓存（.bin）；
     * yt-dlp 原生 .part 中间文件与缓存清理策略（6h 过期）天然兼容。
     *
     * @param onProgress 0..100
     */
    suspend fun downloadAudio(
        ctx: Context,
        pageUrl: String,
        dest: File,
        onProgress: (Int) -> Unit = {},
        cookieHeader: String? = null,
    ): File = withContext(Dispatchers.IO) {
        ensureReady(ctx)
        val parent = dest.parentFile
        if (parent == null || dest.name.endsWith(".part")) {
            throw IllegalArgumentException("下载目标路径不合法")
        }
        val request = YoutubeDLRequest(pageUrl.trim()).apply {
            // 纯音频优先；仅音频不可得时回落 combined（大文件但保底可用）
            addOption("-f", "bestaudio/best")
            addOption("--no-playlist")
            addOption("--socket-timeout", "20")
            addOption("--retries", "3")
            // 上限防护：纯音频通常 5~60MB，300M 足够宽容且防爆盘
            addOption("--max-filesize", "300M")
            // 分片并发下载提速（HLS/dash 音轨常见分段）
            addOption("-N", "4")
            // 逐行刷新进度（外部程序解析标准做法，避免单行 \r 刷新吞进度）
            addOption("--newline")
            addOption("-o", dest.absolutePath)
            cookieHeader?.takeIf { it.isNotBlank() }?.let {
                addOption("--add-headers", "Cookie:$it")
            }
        }
        try {
            YoutubeDL.execute(request) { percent, _, _ ->
                if (percent >= 0f) onProgress(percent.toInt().coerceIn(0, 100))
            }
        } catch (e: java.io.InterruptedIOException) {
            throw IllegalStateException("yt-dlp 下载被中断", e)
        }
        // 校验产物：yt-dlp 正常退出但零字节/极小文件视为失败，让调用方回落流式管线
        if (!dest.isFile || dest.length() < 1_000_000L) {
            dest.delete()
            throw IllegalStateException("yt-dlp 音频下载结果异常")
        }
        dest
    }

    /** 是否为适合 yt-dlp 处理的 URL（http/https 均可；主入口 YouTube 仍走 NewPipe，此处含 YouTube 作为风控兜底）。 */
    fun isEligible(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) return false
        val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: return false
        return host.isNotEmpty() && !host.endsWith(".local")
    }

    /**
     * 更新 yt-dlp 站点解析规则（STABLE 渠道）。
     * 网站改版后旧规则失效导致解析失败时，更新规则是第一自救手段；
     * 下载量约 30MB，由用户显式触发。返回用户可读的结果文案。
     */
    suspend fun updateRules(ctx: Context): String = withContext(Dispatchers.IO) {
        ensureReady(ctx)
        when (YoutubeDL.updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)) {
            YoutubeDL.UpdateStatus.DONE -> "yt-dlp 解析规则已更新到最新版，可重试解析"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp 解析规则已是最新版；失败可能是站点限制或网络问题"
        }
    }

    /** 解析结果：可直接交给播放器的媒体地址 + 必要的请求头。 */
    data class ResolvedMedia(
        val title: String,
        val durationSec: Int,
        val mediaUrl: String,
        val referrer: String,
        val cookie: String,
    )

    /**
     * 解析网页为可播放媒体地址。挑格式优先级：
     * 1) combined（音视频合一，按 tbr 最高）
     * 2) HLS m3u8 master（播放器侧 exoplayer-hls 可播）
     * 3) 任何含音频的流（黑屏可听，保底）
     *
     * @param cookieHeader 站点 Cookie（如 WebView 已登录态），可显著提高需登录站点的成功率；
     *                    格式为 "k=v; k2=v2"。
     */
    suspend fun resolve(ctx: Context, pageUrl: String, cookieHeader: String? = null): ResolvedMedia {
        ensureReady(ctx)
        return withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(pageUrl.trim()).apply {
                // 合集/播放列表页只取单视频，避免多行 JSON 撞坏单视频解析
                addOption("--no-playlist")
                // 网络层超时：防慢站点/死链把解析进程挂死
                addOption("--socket-timeout", "20")
                addOption("--retries", "3")
                if (!cookieHeader.isNullOrBlank()) {
                    addOption("--add-headers", "Cookie:$cookieHeader")
                }
            }
            val info = YoutubeDL.getInfo(request)
            val formats = info.formats.orEmpty()
            val combined = formats.filter { f ->
                val v = f.vcodec
                val a = f.acodec
                !v.isNullOrBlank() && v != "none" && !a.isNullOrBlank() && a != "none" && !f.url.isNullOrBlank()
            }.maxByOrNull { it.tbr }
            val hls = formats.firstOrNull { f ->
                val u = f.url.orEmpty()
                f.ext == "m3u8" || u.contains(".m3u8", ignoreCase = true)
            }
            val audioOnly = formats.filter { f ->
                val a = f.acodec
                !a.isNullOrBlank() && a != "none" && (f.vcodec.isNullOrBlank() || f.vcodec == "none") && !f.url.isNullOrBlank()
            }.maxByOrNull { it.tbr }

            val chosen: VideoFormat? = combined ?: hls ?: audioOnly
            val url = chosen?.url ?: info.url
            if (url.isNullOrBlank()) {
                throw IllegalStateException("yt-dlp 未返回可播放的媒体地址")
            }
            // 透传给播放器的 Cookie 优先用请求头里的（保持与解析一致的登录态）
            val headers = info.httpHeaders.orEmpty()
            val effectiveCookie = headers["Cookie"].orEmpty().ifBlank { cookieHeader.orEmpty() }
            // 记录会话级来源标记：转写音频将优先经 yt-dlp 直下纯音频（downloadAudio）
            synchronized(resolvedOrigins) {
                resolvedOrigins[url] = ClaimedOrigin(pageUrl.trim(), effectiveCookie.ifBlank { null })
            }
            ResolvedMedia(
                title = info.title ?: info.fulltitle ?: "yt-dlp 视频",
                durationSec = info.duration,
                mediaUrl = url,
                referrer = headers["Referer"].orEmpty(),
                cookie = effectiveCookie,
            )
        }
    }

    /** 用户可读的解析失败原因。 */
    fun failureHint(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Unsupported URL", ignoreCase = true) ->
                "该网站暂不在 yt-dlp 支持列表内"
            msg.contains("Sign in", ignoreCase = true) || msg.contains("login", ignoreCase = true) ->
                "该视频需要登录/会员，暂不支持"
            msg.contains("unavailable", ignoreCase = true) || msg.contains("removed", ignoreCase = true) ->
                "视频不可用（可能已删除或地区限制）"
            msg.contains("timed out", ignoreCase = true) || msg.contains("timeout", ignoreCase = true) ->
                "网络超时，请检查网络后重试"
            else -> "yt-dlp 解析失败：${e.message ?: "未知错误"}"
        }
    }
}
