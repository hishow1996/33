package io.translatetube.local.ui.screens

import io.translatetube.local.player.HlsAudioDownloader
import io.translatetube.local.web.WebMediaUrlPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Audio-source categories displayed alongside manually selectable webpage candidates. */
internal enum class WebCandidateAudioHintKind {
    INDEPENDENT,
    LOW_BITRATE_MUXED,
    UNKNOWN,
}

/** Audio-extraction information for one manually selectable webpage media candidate. */
internal data class WebCandidateAudioHint(
    val message: String,
    val kind: WebCandidateAudioHintKind = WebCandidateAudioHintKind.UNKNOWN,
    /** Rendition selected inside an inspected HLS master; it may also appear as another list item. */
    val renditionUrl: String? = null,
)

/**
 * Validates discovered media candidates and inspects HLS master playlists for audio hints.
 * Candidate validation affects status/sorting only; it does not choose a media source for the user.
 */
internal object WebCandidateVerifier {

    private const val MAX_HLS_HINT_BYTES = 256 * 1024

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

    private fun hasVideoExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "m4v", "webm", "flv", "mkv", "mov", "ts", "avi")
    }

    /**
     * Initial hint shown immediately, before an HLS master has been inspected. Direct video files
     * are deliberately not called audio-only or low-bitrate: their tracks cannot be proven from
     * the URL alone.
     */
    fun initialAudioHint(url: String): WebCandidateAudioHint = when {
        WebMediaUrlPolicy.isExplicitAudioOnlyResource(url) -> WebCandidateAudioHint(
            message = "独立音轨 · 纯音频地址",
            kind = WebCandidateAudioHintKind.INDEPENDENT,
        )
        HlsAudioDownloader.isManifestUrl(url) -> WebCandidateAudioHint(
            message = "正在检测 HLS 清单中的音轨类型…",
        )
        hasVideoExtension(url) -> WebCandidateAudioHint(
            message = "直链视频 · 音轨类型待确认",
        )
        else -> WebCandidateAudioHint(
            message = "音轨类型待确认 · 选择后会在解析时检测",
        )
    }

    /**
     * Inspect an HLS master to explain how this chosen source will supply transcription audio.
     * It never chooses a different webpage candidate: an independent rendition is preferred
     * inside this master, otherwise HlsAudioDownloader selects the lowest-bandwidth viable muxed
     * rendition. Media playlists alone do not contain enough information to classify their tracks.
     */
    fun inspectAudioHint(
        client: OkHttpClient,
        url: String,
        referer: String,
        cookie: String?,
        userAgent: String?,
    ): WebCandidateAudioHint {
        val initial = initialAudioHint(url)
        if (!HlsAudioDownloader.isManifestUrl(url)) return initial

        return runCatching {
            val request = Request.Builder().url(url).get().apply {
                header("Range", "bytes=0-" + (MAX_HLS_HINT_BYTES - 1))
                header("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                if (referer.startsWith("http")) header("Referer", referer)
                cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            }.build()
            client.newCall(request).execute().use responseUse@ { response ->
                if (response.code !in 200..299) return@responseUse initial
                val body = response.body ?: return@responseUse initial
                val manifest = readBoundedPrefix(body.byteStream(), MAX_HLS_HINT_BYTES)
                if (manifest.lineSequence().none { it.trim().equals("#EXTM3U", ignoreCase = true) }) {
                    return@responseUse initial
                }

                val finalUrl = response.request.url.toString()
                val selection = runCatching {
                    HlsAudioDownloader.selectMediaPlaylistWithKind(finalUrl, manifest)
                }.getOrNull() ?: return@responseUse WebCandidateAudioHint(
                    message = "HLS 分段清单 · 音轨类型待确认",
                )
                when (selection.sourceKind) {
                    HlsAudioDownloader.AudioSourceKind.INDEPENDENT -> WebCandidateAudioHint(
                        message = "检测到独立音轨 · 提取时优先使用",
                        kind = WebCandidateAudioHintKind.INDEPENDENT,
                        renditionUrl = selection.url,
                    )
                    HlsAudioDownloader.AudioSourceKind.MUXED -> WebCandidateAudioHint(
                        message = "没有独立音轨 · 提取时使用低码率音画混合流",
                        kind = WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
                        renditionUrl = selection.url,
                    )
                    HlsAudioDownloader.AudioSourceKind.UNKNOWN -> WebCandidateAudioHint(
                        message = "HLS 分段清单 · 音轨类型待确认",
                    )
                }
            }
        }.getOrDefault(initial)
    }

    private fun readBoundedPrefix(input: java.io.InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        input.use { source ->
            while (total < maxBytes) {
                val count = source.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
