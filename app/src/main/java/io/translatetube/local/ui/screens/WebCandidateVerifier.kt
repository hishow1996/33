package io.translatetube.local.ui.screens

import io.translatetube.local.player.HlsAudioDownloader
import io.translatetube.local.web.WebMediaUrlPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

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
        if (head.httpStatus?.let { it in 200..299 } == true) {
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
        if (range.httpStatus?.let { it in 200..299 } == true &&
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

    /** MP4-family direct links have a readable header; other containers are not parsed yet. */
    internal fun isProbeableDirectVideo(url: String): Boolean {
        if (HlsAudioDownloader.isManifestUrl(url) || WebMediaUrlPolicy.isExplicitAudioOnlyResource(url)) return false
        val path = url.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mov")
    }

    /**
     * Initial hint shown immediately, before the source has been inspected. A source is never
     * called audio-only or low-bitrate from its URL alone: its tracks can only be proven by
     * reading the HLS master or the MP4 header.
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
        isProbeableDirectVideo(url) -> WebCandidateAudioHint(
            message = "正在读取文件头，检测音轨和视频码率…",
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
        if (!HlsAudioDownloader.isManifestUrl(url)) {
            return if (isProbeableDirectVideo(url)) {
                inspectDirectMp4(client, url, referer, cookie, userAgent)
            } else {
                initial
            }
        }

        // Keep structured request evidence even when parsing throws half-way through. Never store
        // query values: HLS URLs commonly carry short-lived signatures and authentication tokens.
        val diagnostics = linkedMapOf<String, String>()
        diagnostics["检测步骤"] = "HLS 主清单音轨类型检测"
        diagnostics["请求方法"] = "GET"
        diagnostics["请求 Range"] = "bytes=0-${MAX_HLS_HINT_BYTES - 1}"
        diagnostics["是否携带 Referer"] = referer.startsWith("http", ignoreCase = true).toString()
        diagnostics["是否携带 Cookie"] = (!cookie.isNullOrBlank()).toString()
        diagnostics["是否携带自定义 User-Agent"] = (!userAgent.isNullOrBlank()).toString()
        diagnostics["检测清单最大读取字节数"] = MAX_HLS_HINT_BYTES.toString()
        diagnostics.putAll(safeUrlDiagnostics(url, "请求地址"))

        return runCatching {
            val request = Request.Builder().url(url).get().apply {
                header("Range", "bytes=0-" + (MAX_HLS_HINT_BYTES - 1))
                header("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                if (referer.startsWith("http")) header("Referer", referer)
                cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            }.build()
            client.newCall(request).execute().use responseUse@ { response ->
                diagnostics["HTTP 状态码"] = response.code.toString()
                diagnostics["HTTP 状态说明"] = response.message.ifBlank { "服务器未提供" }
                diagnostics["响应 Content-Type"] = response.header("Content-Type").orEmpty().ifBlank { "未提供" }
                diagnostics["响应 Content-Length"] = response.header("Content-Length")
                    ?: response.body?.contentLength()?.takeIf { it >= 0L }?.toString()
                    ?: "未提供"
                diagnostics["响应 Content-Range"] = response.header("Content-Range").orEmpty().ifBlank { "未提供" }
                diagnostics["响应 Accept-Ranges"] = response.header("Accept-Ranges").orEmpty().ifBlank { "未提供" }
                diagnostics["响应 Retry-After"] = response.header("Retry-After").orEmpty().ifBlank { "未提供" }
                diagnostics["响应 Server"] = response.header("Server").orEmpty().ifBlank { "未提供" }
                diagnostics["是否发生重定向"] = (response.priorResponse != null).toString()
                diagnostics.putAll(safeUrlDiagnostics(response.request.url.toString(), "最终响应地址"))
                if (response.code !in 200..299) {
                    // HTTP errors often return a short useful reason or JSON error code.
                    // Keep only a sanitized summary and never persist raw HTML/response bodies.
                    val errorBody = response.body?.let { body ->
                        runCatching { readBoundedPrefix(body.byteStream(), 4 * 1024) }.getOrDefault("")
                    }.orEmpty()
                    diagnostics["HTTP 错误响应体摘要"] = summarizeHttpErrorBody(errorBody)
                    diagnostics["HTTP 错误响应体实际读取字节数"] =
                        errorBody.toByteArray(Charsets.UTF_8).size.toString()
                    // A failed HTTP status is a detection failure even though OkHttp does not
                    // throw for it. Preserve a real diagnostic cause so the caller can persist
                    // this failure in the app log instead of recording only a UI hint.
                    val failure = java.io.IOException(
                        "HLS 主清单请求失败：HTTP ${response.code} ${response.message}; " +
                            "Content-Type=${response.header("Content-Type") ?: "未提供"}; " +
                            "Content-Range=${response.header("Content-Range") ?: "未提供"}; " +
                            "响应摘要=${diagnostics["HTTP 错误响应体摘要"]}"
                    )
                    return@responseUse WebCandidateAudioHint(
                        message = "HLS 检测失败 · HTTP ${response.code}",
                        kind = WebCandidateAudioHintKind.FAILED,
                        failure = failure,
                    )
                }
                val body = response.body ?: return@responseUse WebCandidateAudioHint(
                    message = "HLS 检测失败 · 服务器没有返回清单内容",
                    kind = WebCandidateAudioHintKind.FAILED,
                    failure = java.io.EOFException(
                        "HLS 主清单响应体为空（HTTP ${response.code}）。"
                    ),
                )
                val manifest = readBoundedPrefix(body.byteStream(), MAX_HLS_HINT_BYTES)
                val manifestLines = manifest.removePrefix("\uFEFF")
                    .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                diagnostics["实际读取清单字节数"] = manifest.toByteArray(Charsets.UTF_8).size.toString()
                diagnostics["响应内容特征"] = classifyManifestPayload(manifest)
                diagnostics["是否包含 #EXTM3U"] = manifestLines.any {
                    it.equals("#EXTM3U", ignoreCase = true)
                }.toString()
                diagnostics["EXT-X-STREAM-INF 数量"] = manifestLines.count {
                    it.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)
                }.toString()
                diagnostics["AUDIO 类型 EXT-X-MEDIA 数量"] = manifestLines.count {
                    it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                        it.substringAfter(':').contains("TYPE=\"AUDIO\"", ignoreCase = true)
                }.toString()
                diagnostics["EXTINF 片段数量"] = manifestLines.count {
                    it.startsWith("#EXTINF:", ignoreCase = true)
                }.toString()
                diagnostics["是否包含 EXT-X-ENDLIST"] = manifestLines.any {
                    it.equals("#EXT-X-ENDLIST", ignoreCase = true)
                }.toString()
                if (manifestLines.none { it.equals("#EXTM3U", ignoreCase = true) }) {
                    return@responseUse WebCandidateAudioHint(
                        message = "HLS 检测失败 · 响应内容不是有效的 M3U8 清单",
                        kind = WebCandidateAudioHintKind.FAILED,
                        failure = IllegalStateException(
                            "HLS 响应内容不是有效的 M3U8 清单：缺少 #EXTM3U 标记。"
                        ),
                    )
                }

                val finalUrl = response.request.url.toString()
                val selection = HlsAudioDownloader.selectMediaPlaylistWithKind(finalUrl, manifest)
                    ?: return@responseUse WebCandidateAudioHint(
                        message = "HLS 子清单 · 未发现可比较的画质版本，音轨类型待确认",
                        kind = WebCandidateAudioHintKind.UNKNOWN,
                    )
                diagnostics["最终判断"] = selection.sourceKind.displayName
                diagnostics["选择的音轨来源"] = if (
                    selection.sourceKind == HlsAudioDownloader.AudioSourceKind.INDEPENDENT
                ) "独立音轨地址" else "音画混合流地址"
                diagnostics["选中流声明带宽 bps"] =
                    selection.declaredBandwidth?.toString() ?: "未声明/独立音轨不继承视频码率"
                diagnostics["选中流分辨率"] = selection.resolution ?: "未声明/不适用"
                diagnostics.putAll(safeUrlDiagnostics(selection.url, "选中媒体地址"))
                // Track details (audio codec/channels/language, video bitrate and resolution) come
                // from the same master text. A parser problem must not turn a good selection
                // into a failed check, so it degrades to a hint without those details.
                val description = runCatching {
                    HlsMasterSummaryParser.describe(
                        summary = HlsMasterSummaryParser.parse(manifest),
                        masterUrl = finalUrl,
                        selectedUrl = selection.url,
                        independentAudio = selection.sourceKind == HlsAudioDownloader.AudioSourceKind.INDEPENDENT,
                        declaredBandwidth = selection.declaredBandwidth,
                        declaredResolution = selection.resolution,
                    )
                }.getOrElse {
                    HlsTrackDescription(
                        info = WebCandidateMediaInfo(container = "HLS", hasAudio = true),
                        audioOnlyVariant = false,
                    )
                }
                when (selection.sourceKind) {
                    HlsAudioDownloader.AudioSourceKind.INDEPENDENT ->
                        WebCandidateAudioHints.forHls(description, independentAudio = true, renditionUrl = selection.url)
                    HlsAudioDownloader.AudioSourceKind.MUXED ->
                        WebCandidateAudioHints.forHls(description, independentAudio = false, renditionUrl = selection.url)
                    HlsAudioDownloader.AudioSourceKind.UNKNOWN -> WebCandidateAudioHint(
                        message = "HLS 子清单 · 未发现可比较的画质版本，音轨类型待确认",
                        kind = WebCandidateAudioHintKind.UNKNOWN,
                    )
                }
            }
        }.fold(
            onSuccess = { hint ->
                hint.copy(diagnosticContext = diagnostics + hint.diagnosticContext)
            },
            onFailure = { error ->
                failedAudioHint(url, error, diagnostics)
            },
        )
    }

    /**
     * Reads the header of a direct MP4/MOV link with a few small range requests (never the media
     * data) to report whether it has an audio track and what the video bitrate is. Other
     * containers and servers without Range support get an honest "cannot parse" hint, not a guess.
     */
    private fun inspectDirectMp4(
        client: OkHttpClient,
        url: String,
        referer: String,
        cookie: String?,
        userAgent: String?,
    ): WebCandidateAudioHint {
        val diagnostics = linkedMapOf<String, String>()
        diagnostics["检测步骤"] = "直链 MP4 文件头音轨检测"
        diagnostics["请求方法"] = "GET（分段 Range）"
        diagnostics["是否携带 Referer"] = referer.startsWith("http", ignoreCase = true).toString()
        diagnostics["是否携带 Cookie"] = (!cookie.isNullOrBlank()).toString()
        diagnostics["是否携带自定义 User-Agent"] = (!userAgent.isNullOrBlank()).toString()
        diagnostics.putAll(safeUrlDiagnostics(url, "请求地址"))

        val headers = buildMap {
            if (referer.startsWith("http")) put("Referer", referer)
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        // Reading a faststart header can take a few seconds on a slow link, so this probe gets
        // a longer overall limit than the quick HEAD/Range verification client.
        val probeClient = client.newBuilder()
            .callTimeout(25, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        return runCatching {
            when (val result = Mp4SourceInspector.inspect(OkHttpRangeReader(probeClient, url, headers))) {
                is Mp4InspectResult.Parsed -> WebCandidateAudioHints.forDirectMp4(result.info)
                is Mp4InspectResult.NotMp4 -> WebCandidateAudioHint(
                    message = when (result.container) {
                        null -> "直链视频 · 文件头不是 MP4/MOV 结构，音轨类型待确认"
                        "网页/文本" -> "该地址返回的是网页或文本，不是视频文件"
                        else -> "直链视频 · 实际是 ${result.container} 格式，暂不解析音轨 · 音轨类型待确认"
                    },
                    diagnosticContext = mapOf(
                        "检测结果" to result.reason,
                        "识别到的容器" to (result.container ?: "未识别"),
                    ),
                )
                is Mp4InspectResult.Unreadable -> WebCandidateAudioHint(
                    message = "直链视频 · ${result.reason} · 音轨类型待确认",
                    diagnosticContext = mapOf("检测结果" to result.reason),
                )
            }
        }.fold(
            onSuccess = { hint -> hint.copy(diagnosticContext = diagnostics + hint.diagnosticContext) },
            onFailure = { error -> failedAudioHint(url, error, diagnostics) },
        )
    }

    /** Converts request/parser errors into a terminal state so the UI never spins forever. */
    fun failedAudioHint(
        url: String,
        error: Throwable,
        diagnosticContext: Map<String, String> = emptyMap(),
    ): WebCandidateAudioHint {
        val detail = when (error) {
            is java.net.SocketTimeoutException -> "请求超时：${error.message.orEmpty()}"
            is java.net.UnknownHostException -> "域名无法解析或不可访问：${error.message.orEmpty()}"
            is javax.net.ssl.SSLException -> "HTTPS/TLS 连接失败：${error.message.orEmpty()}"
            is java.net.ConnectException -> "连接服务器失败：${error.message.orEmpty()}"
            is java.io.EOFException -> "响应提前结束或内容为空：${error.message.orEmpty()}"
            is java.io.IOException -> "网络/HTTP 请求失败：${error.message.orEmpty()}"
            is IllegalStateException -> error.message?.takeIf { it.isNotBlank() } ?: "状态校验失败"
            is IllegalArgumentException -> error.message?.takeIf { it.isNotBlank() } ?: "请求参数或清单格式无效"
            else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
        }.take(220)
        val prefix = if (HlsAudioDownloader.isManifestUrl(url)) "HLS 检测失败" else "音轨检测失败"
        return WebCandidateAudioHint(
            message = "$prefix · $detail",
            kind = WebCandidateAudioHintKind.FAILED,
            failure = error,
            diagnosticContext = diagnosticContext + mapOf(
                "失败异常类型" to error.javaClass.name,
                "失败异常消息" to (error.message ?: "无异常消息").take(800),
                "失败分类" to when (error) {
                    is java.net.SocketTimeoutException -> "网络超时"
                    is java.net.UnknownHostException -> "DNS/域名解析"
                    is javax.net.ssl.SSLException -> "TLS/证书/HTTPS"
                    is java.net.ConnectException -> "TCP 连接"
                    is java.io.EOFException -> "空响应/提前结束"
                    is java.io.IOException -> "I/O/HTTP 请求"
                    is IllegalArgumentException -> "参数或格式校验"
                    else -> "清单解析或未知异常"
                },
            ),
        )
    }

    /**
     * Gives engineers enough URL identity to reproduce a failure without storing query values,
     * which often contain expiring credentials. Long/suspicious path segments are also masked.
     */
    private fun safeUrlDiagnostics(rawUrl: String, label: String): Map<String, String> {
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull()
            ?: return mapOf(label to "URL 无法解析")
        val pathSegments = uri.rawPath.orEmpty().split('/').filter(String::isNotBlank)
        val sensitivePathToken = Regex("""(?i)(token|signature|auth|secret|password|credential|session|jwt|expires|access.?key)""")
        val safePath = pathSegments.map { segment ->
            when {
                sensitivePathToken.containsMatchIn(segment) -> "[疑似凭据路径段已隐藏]"
                segment.length > 48 -> "[长路径段已隐藏:${segment.length}字符]"
                else -> segment
            }
        }.joinToString("/", prefix = "/")
        val queryNames = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .map { it.substringBefore('=').take(80) }
            .distinct()
        val extension = pathSegments.lastOrNull()?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 12 }
            ?.lowercase(java.util.Locale.ROOT)
            ?: "无"
        return mapOf(
            "${label}协议" to uri.scheme.orEmpty().ifBlank { "未知" },
            "${label}主机名" to uri.host.orEmpty().ifBlank { "未知" },
            "${label}路径（已脱敏）" to safePath.take(240),
            "${label}文件扩展名" to extension,
            "${label}查询参数名（不含值）" to queryNames.joinToString(", ").ifBlank { "无" }.take(300),
        )
    }

    /** Return a useful HTTP error summary without persisting response HTML or credentials. */
    private fun summarizeHttpErrorBody(value: String): String {
        val prefix = value.removePrefix("\uFEFF").trim()
        if (prefix.isBlank()) return "响应体为空"
        if (
            prefix.startsWith("<!doctype html", ignoreCase = true) ||
            prefix.startsWith("<html", ignoreCase = true) ||
            prefix.startsWith("<")
        ) {
            val title = Regex("""(?is)<title[^>]*>(.*?)</title>""")
                .find(prefix)?.groupValues?.getOrNull(1)
                ?.replace(Regex("""<[^>]+>"""), " ")
                ?.trim()
            return title?.takeIf { it.isNotBlank() }?.let {
                "HTML 错误页标题：" + sanitizeDiagnosticSnippet(it)
            } ?: "HTML 错误页（正文未记录）"
        }
        if (prefix.startsWith("{") || prefix.startsWith("[")) {
            val fields = Regex(
                """"(code|type|error|message|detail|title|success)"\s*:\s*("(?:\\.|[^"\\])*"|true|false|null|-?\d+)""",
                RegexOption.IGNORE_CASE,
            ).findAll(prefix).take(8).map { match ->
                val key = match.groupValues[1]
                val rawValue = match.groupValues[2].trim('"')
                    .replace("\\n", " ")
                    .replace("\\\"", "\"")
                key + "=" + sanitizeDiagnosticSnippet(rawValue)
            }.toList()
            return if (fields.isNotEmpty()) {
                "JSON 错误字段：" + fields.joinToString("; ").take(240)
            } else {
                "JSON 错误响应（未识别常见错误字段；正文未记录）"
            }
        }
        val firstLine = prefix.lineSequence().firstOrNull(String::isNotBlank).orEmpty()
        return sanitizeDiagnosticSnippet(firstLine).ifBlank { "服务器返回了文本，但摘要为空" }
    }

    /** Redact URLs and credential-like values from short server messages before saving them. */
    private fun sanitizeDiagnosticSnippet(value: String): String {
        var safe = value
        safe = Regex("""(?i)\b(Bearer|Basic)\s+\S+""")
            .replace(safe) { it.groupValues[1] + " [已隐藏]" }
        safe = Regex("""(?i)\b(token|api[_-]?key|signature|sig|authorization|cookie|secret|password|credential|session|jwt|expires)\b\s*[:=]\s*["']?[^&\s,"'<>]+""")
            .replace(safe) { it.groupValues[1] + "=[已隐藏]" }
        safe = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            .replace(safe, "[URL 已隐藏]")
        safe = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9_./+=-]{48,}(?![A-Za-z0-9])""")
            .replace(safe, "[长令牌已隐藏]")
        return safe.replace(Regex("""\s+"""), " ").trim().take(240)
    }

    /** Classifies the response prefix without copying potentially sensitive HTML/JSON into logs. */
    private fun classifyManifestPayload(value: String): String {
        val prefix = value.removePrefix("\uFEFF").trimStart()
        return when {
            prefix.isBlank() -> "空白响应"
            prefix.startsWith("#EXTM3U", ignoreCase = true) -> "M3U8 清单文本"
            prefix.startsWith("<!doctype html", ignoreCase = true) ||
                prefix.startsWith("<html", ignoreCase = true) -> "HTML 网页（常见于登录页/防护拦截页）"
            prefix.startsWith("{") || prefix.startsWith("[") -> "JSON 响应（可能为接口错误信息）"
            prefix.startsWith("<?xml", ignoreCase = true) -> "XML 响应（不是标准 M3U8 开头）"
            else -> "普通文本/未知内容（不是标准 M3U8 开头）"
        }
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
