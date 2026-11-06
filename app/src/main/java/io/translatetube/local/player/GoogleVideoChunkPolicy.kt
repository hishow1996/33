package io.translatetube.local.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * 油管（googlevideo）对「不带 Range、一个请求读完整条」的下载会限速，速度可能只有几十 KB/s；
 * 按约 10MB 一块、每块一个 Range 请求拉取则不受这个限制（yt-dlp 对油管也是这么做的）。
 * 这里只放可单测的纯逻辑：哪些地址要分块、每块的 Range 头、以及 Content-Range 解析。
 */
internal object GoogleVideoChunkPolicy {
    const val CHUNK_BYTES: Long = 10L * 1024L * 1024L

    /** 仅对 googlevideo 的媒体地址启用分块；其他站点保持原有整段下载。 */
    fun shouldChunk(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: return false
        return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    /**
     * 从 [start] 起的一块。已知总长度时最后一块不越界；未知时按整块请求，
     * 服务器会在 Content-Range 里给出真实范围和总长度。
     */
    fun rangeHeader(start: Long, totalBytes: Long): String {
        require(start >= 0L) { "start must not be negative" }
        val unclamped = if (start > Long.MAX_VALUE - CHUNK_BYTES) Long.MAX_VALUE else start + CHUNK_BYTES - 1L
        val end = if (totalBytes > 0L) minOf(unclamped, totalBytes - 1L) else unclamped
        return "bytes=$start-$end"
    }

    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private val contentRangeRegex = Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

    fun parseContentRange(header: String?): ContentRange? {
        val match = contentRangeRegex.matchEntire(header?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (start > end) return null
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else (totalText.toLongOrNull() ?: return null)
        if (total != null && (total <= 0L || end >= total)) return null
        return ContentRange(start, end, total)
    }
}

/** 服务器没有按 Range 返回分块（例如直接回 200 整条）；调用方应回退到原有的整段下载。 */
internal class GoogleVideoRangeUnsupportedException(message: String) : IllegalStateException(message)
