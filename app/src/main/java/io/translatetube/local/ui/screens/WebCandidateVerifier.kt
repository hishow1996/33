package io.translatetube.local.ui.screens

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 网页视频候选验证器（参考 VBrowser 的 sniff-verify 思路）：
 * WebView 探测到的候选 URL 混着广告、统计接口、失效地址，用户无从分辨；
 * 对每个候选后台发一次极小的 HEAD（不支持 HEAD 的站点回退 Range: bytes=0-0 的
 * GET，只拉 1 字节）确认 Content-Type 真的是视频/音频流，验证通过的候选
 * 才进入自动播放与优先展示。
 */
internal object WebCandidateVerifier {

    private val MEDIA_EXTENSIONS = setOf(
        "m3u8", "mpd", "mp4", "m4v", "webm", "flv", "mkv", "mov", "ts",
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav",
    )

    fun createVerifiedSet(): MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 验证候选 URL 是否是可访问的媒体流。HEAD 优先；405/501 或无 Content-Type
     * 时回退 1 字节 Range GET。带页面 Referer/Cookie/UA，与播放器请求一致，
     * 防护站点的判定结果对播放同样成立。
     */
    fun verify(
        client: OkHttpClient,
        url: String,
        referer: String,
        cookie: String?,
        userAgent: String?,
    ): Boolean {
        val headers = buildMap {
            if (referer.startsWith("http")) put("Referer", referer)
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val headCode = runCatching {
            client.newCall(
                Request.Builder().url(url).head().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            ).execute().use { it.code to (it.headers["Content-Type"] ?: "") }
        }.getOrNull()
        if (headCode != null && headCode.first in 200..299) {
            if (isMediaContentType(headCode.second) || hasMediaExtension(url)) return true
            if (headCode.second.isNotBlank() && !isMediaContentType(headCode.second)) return false
        }
        // HEAD 被拒或无类型信息：Range GET 只拉 1 字节确认
        val rangeHeaders = headers + ("Range" to "bytes=0-0")
        val getCode = runCatching {
            client.newCall(
                Request.Builder().url(url).get().apply { rangeHeaders.forEach { (k, v) -> header(k, v) } }.build()
            ).execute().use { it.code to (it.headers["Content-Type"] ?: "") }
        }.getOrNull() ?: return false
        return getCode.first in 200..299 && (isMediaContentType(getCode.second) || hasMediaExtension(url))
    }

    private fun isMediaContentType(contentType: String): Boolean {
        val ct = contentType.substringBefore(';').trim().lowercase()
        if (ct.startsWith("video/") || ct.startsWith("audio/")) return true
        if (ct.contains("mpegurl") || ct.contains("dash+xml")) return true
        return false
    }

    private fun hasMediaExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in MEDIA_EXTENSIONS
    }
}
