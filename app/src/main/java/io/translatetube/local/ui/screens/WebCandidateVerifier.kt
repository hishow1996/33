package io.translatetube.local.ui.screens

import io.translatetube.local.player.HlsAudioDownloader
import io.translatetube.local.web.WebMediaUrlPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Audio-source categories displayed alongside manually selectable webpage candidates. */
internal enum class WebCandidateAudioHintKind {
    DETECTING,
    INDEPENDENT,
    LOW_BITRATE_MUXED,
    UNKNOWN,
    FAILED,
}

/** Audio-extraction information for one manually selectable webpage media candidate. */
internal data class WebCandidateAudioHint(
    val message: String,
    val kind: WebCandidateAudioHintKind = WebCandidateAudioHintKind.UNKNOWN,
    /** Rendition selected inside an inspected HLS master; it may also appear as another list item. */
    val renditionUrl: String? = null,
)

/** Detailed HTTP evidence for a candidate source check, without retaining its full URL. */
internal data class WebCandidateVerificationResult(
    val verified: Boolean,
    val method: String,
    val httpStatus: Int? = null,
    val contentType: String = "",
    val reason: String,
    val headStatus: Int? = null,
    val headContentType: String = "",
    val failure: Throwable? = null,
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
    ): Boolean = verifyDetailed(client, url, referer, cookie, userAgent).verified

    /**
     * Keeps the same HEAD-first / one-byte Range GET decision as [verify], but retains
     * the HTTP status, content type, method and exception so the app log can explain a rejection.
     */
    fun verifyDetailed(
        client: OkHttpClient,
        url: String,
        referer: String,
        cookie: String?,
        userAgent: String?,
    ): WebCandidateVerificationResult {
        val headers = buildMap {
            if (referer.startsWith("http")) put("Referer", referer)
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }

        fun probe(method: String, rangeGet: Boolean): WebCandidateVerificationResult {
            return try {
                val builder = Request.Builder().url(url)
                if (rangeGet) {
                    builder.get().header("Range", "bytes=0-0")
                } else {
                    builder.head()
                }
                headers.forEach { (name, value) -> builder.header(name, value) }
                client.newCall(builder.build()).execute().use { response ->
                    WebCandidateVerificationResult(
                        verified = false,
                        method = method,
                        httpStatus = response.code,
                        contentType = response.header("Content-Type").orEmpty(),
                        reason = "HTTP ${response.code}",
                    )
                }
            } catch (failure: Exception) {
                WebCandidateVerificationResult(
                    verified = false,
                    method = method,
                    reason = "请求异常：${failure.javaClass.simpleName}",
                    failure = failure,
                )
            }
        }

        val head = probe("HEAD", rangeGet = false)
        if (head.httpStatus in 200..299) {
            if (isMediaContentType(head.contentType) || hasMediaExtension(url)) {
                return head.copy(
                    verified = true,
                    reason = "HEAD 响应可访问，Content-Type 或媒体扩展名符合媒体源特征",
                )
            }
            if (head.contentType.isNotBlank() && !isMediaContentType(head.contentType)) {
                return head.copy(
                    verified = false,
                    reason = "HEAD 返回的 Content-Type 不是媒体类型",
                )
            }
        }

        // HEAD 不可用、无内容类型或被服务器拒绝时，仅请求 1 字节确认媒体响应。
        val range = probe("Range GET", rangeGet = true)
        if (range.httpStatus in 200..299 &&
            (isMediaContentType(range.contentType) || hasMediaExtension(url))
        ) {
            return range.copy(
                verified = true,
                reason = "Range GET 响应可访问，Content-Type 或媒体扩展名符合媒体源特征",
                headStatus = head.httpStatus,
                headContentType = head.contentType,
            )
        }

        val statusReason = when (range.httpStatus) {
            401 -> "Range GET 返回 HTTP 401：请求未通过身份验证，可能需要登录或 Cookie"
            403 -> "Range GET 返回 HTTP 403：服务器拒绝访问，可能有防盗链、权限或签名限制"
            404 -> "Range GET 返回 HTTP 404：媒体地址不存在或已过期"
            410 -> "Range GET 返回 HTTP 410：资源已失效"
            429 -> "Range GET 返回 HTTP 429：请求过于频繁，可能触发站点限流"
            null -> "Range GET 没有收到有效 HTTP 响应"
            else -> "Range GET 未能确认媒体响应（HTTP ${range.httpStatus}）"
        }
        return range.copy(
            verified = false,
            reason = if (range.failure != null) "Range GET 请求异常" else statusReason,
            headStatus = head.httpStatus,
            headContentType = head.contentType,
            failure = range.failure ?: head.failure,
        )
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
            kind = WebCandidateAudioHintKind.DETECTING,
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
                if (response.code !in 200..299) {
                    return@responseUse WebCandidateAudioHint(
                        message = "HLS 检测失败 · HTTP ${response.code}",
                        kind = WebCandidateAudioHintKind.FAILED,
                    )
                }
                val body = response.body ?: return@responseUse WebCandidateAudioHint(
                    message = "HLS 检测失败 · 服务器没有返回清单内容",
                    kind = WebCandidateAudioHintKind.FAILED,
                )
                val manifest = readBoundedPrefix(body.byteStream(), MAX_HLS_HINT_BYTES)
                if (manifest.lineSequence().none { it.trim().equals("#EXTM3U", ignoreCase = true) }) {
                    return@responseUse WebCandidateAudioHint(
                        message = "HLS 检测失败 · 响应内容不是有效的 M3U8 清单",
                        kind = WebCandidateAudioHintKind.FAILED,
                    )
                }

                val finalUrl = response.request.url.toString()
                val selection = HlsAudioDownloader.selectMediaPlaylistWithKind(finalUrl, manifest)
                    ?: return@responseUse WebCandidateAudioHint(
                        message = "HLS 子清单 · 未发现可比较的画质版本，音轨类型待确认",
                        kind = WebCandidateAudioHintKind.UNKNOWN,
                    )
                when (selection.sourceKind) {
                    HlsAudioDownloader.AudioSourceKind.INDEPENDENT -> WebCandidateAudioHint(
                        message = "检测到独立音轨 · 不需要下载视频画面",
                        kind = WebCandidateAudioHintKind.INDEPENDENT,
                        renditionUrl = selection.url,
                    )
                    HlsAudioDownloader.AudioSourceKind.MUXED -> {
                        val details = buildList {
                            selection.resolution?.let { add(formatResolution(it)) }
                            selection.declaredBandwidth?.let { add("清单声明 ${formatDeclaredBandwidth(it)}") }
                        }.joinToString(" · ")
                        WebCandidateAudioHint(
                            message = "混合音视频 · 已选择低码率版本" +
                                if (details.isNotBlank()) " · $details" else " · 清晰度/码率未知",
                            kind = WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
                            renditionUrl = selection.url,
                        )
                    }
                    HlsAudioDownloader.AudioSourceKind.UNKNOWN -> WebCandidateAudioHint(
                        message = "HLS 子清单 · 未发现可比较的画质版本，音轨类型待确认",
                        kind = WebCandidateAudioHintKind.UNKNOWN,
                    )
                }
            }
        }.getOrElse { error ->
            failedAudioHint(url, error)
        }
    }

    /** Converts request/parser errors into a terminal state so the UI never spins forever. */
    fun failedAudioHint(url: String, error: Throwable): WebCandidateAudioHint {
        val detail = when (error) {
            is java.net.SocketTimeoutException -> "请求超时"
            is java.net.UnknownHostException -> "域名无法访问"
            is javax.net.ssl.SSLException -> "HTTPS 连接失败"
            is java.io.IOException -> "网络请求失败"
            else -> error.message?.takeIf { it.isNotBlank() }?.take(64) ?: error.javaClass.simpleName
        }
        val prefix = if (HlsAudioDownloader.isManifestUrl(url)) "HLS 检测失败" else "音轨检测失败"
        return WebCandidateAudioHint(
            message = "$prefix · $detail",
            kind = WebCandidateAudioHintKind.FAILED,
        )
    }

    private fun formatResolution(value: String): String {
        val height = value.substringAfter('x', "").toIntOrNull()
        return if (height != null && height > 0) "${height}p" else value
    }

    private fun formatDeclaredBandwidth(bitsPerSecond: Long): String = when {
        bitsPerSecond >= 1_000_000L ->
            String.format(java.util.Locale.ROOT, "%.2f Mbps", bitsPerSecond / 1_000_000.0)
        bitsPerSecond >= 1_000L -> "${bitsPerSecond / 1_000L} kbps"
        else -> "$bitsPerSecond bps"
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
