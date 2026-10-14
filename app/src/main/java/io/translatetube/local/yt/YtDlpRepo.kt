package io.translatetube.local.yt

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * yt-dlp 通用站点提取封装：B站 / TikTok / Twitter 等非 YouTube 网站的视频地址解析。
 *
 * 设计要点：
 * - YouTube 仍优先走 NewPipeExtractor（快、稳），本类只兜非 YouTube 站点；
 * - 只做「解析出可播放的媒体直链」，后续音频提取/转写/配音完全复用现有管线；
 * - 只挑「音视频合一」的可播格式（combined mp4 / HLS m3u8），
 *   分离流（video-only）在播放器侧无法直接合流，故不采用；
 * - 解析出的 URL 带站点签名会过期，调用方必须立即播放，不做长期缓存；
 * - 首次调用会解包 yt-dlp 运行时（约 2~5 秒，仅一次）。
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

    /** 是否为适合 yt-dlp 处理的 URL（http/https 且非 YouTube——YouTube 走 NewPipe）。 */
    fun isEligible(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) return false
        val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: return false
        return !(host.contains("youtube.com") || host.contains("youtu.be"))
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
     */
    suspend fun resolve(ctx: Context, pageUrl: String): ResolvedMedia {
        ensureReady(ctx)
        return withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest(pageUrl.trim())
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
            val headers = info.httpHeaders.orEmpty()
            ResolvedMedia(
                title = info.title ?: info.fulltitle ?: "yt-dlp 视频",
                durationSec = info.duration,
                mediaUrl = url,
                referrer = headers["Referer"].orEmpty(),
                cookie = headers["Cookie"].orEmpty(),
            )
        }
    }

    /** 用户可读的解析失败原因。 */
    fun failureHint(e: Throwable): String = when {
        e.message?.contains("Unsupported URL", ignoreCase = true) == true ->
            "该网站暂不在 yt-dlp 支持列表内"
        e.message?.contains("Sign in", ignoreCase = true) ||
            e.message?.contains("login", ignoreCase = true) == true ->
            "该视频需要登录/会员，暂不支持"
        e.message?.contains("unavailable", ignoreCase = true) ||
            e.message?.contains("removed", ignoreCase = true) == true ->
            "视频不可用（可能已删除或地区限制）"
        else -> "yt-dlp 解析失败：${e.message ?: "未知错误"}"
    }
}
