package io.translatetube.local.web

import java.net.URI
import java.util.Locale

internal enum class WebMediaKind(val displayName: String) {
    HLS("HLS 分段流"),
    DASH("DASH 自适应流"),
    VIDEO_FILE("视频文件"),
    AUDIO_FILE("音频文件"),
    MEDIA_ELEMENT("网页播放器媒体源"),
}

internal data class WebMediaCandidate(
    val url: String,
    val kind: WebMediaKind,
    val mimeType: String? = null,
    val pageUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val encryptedHint: Boolean = false,
)

/** URL/MIME classifier separated from WebView to make media sniffing rules deterministic and testable. */
internal object WebMediaCandidatePolicy {
    private val videoExtensions = setOf("mp4", "m4v", "webm", "mov", "ogv", "mkv")
    private val audioExtensions = setOf("m4a", "mp3", "aac", "oga", "ogg", "wav", "flac", "opus")
    private val forwardableHeaders = setOf(
        "user-agent", "referer", "origin", "cookie", "authorization", "accept-language"
    )

    fun classify(
        url: String,
        mimeType: String? = null,
        mediaElement: String? = null,
        pageUrl: String = "",
    ): WebMediaCandidate? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if ((!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) return null

        val path = uri.path.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        fun queryValue(names: Set<String>): String? = runCatching {
            uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                val name = part.substringBefore('=').lowercase(Locale.ROOT)
                if (name in names) {
                    java.net.URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
                } else null
            }
        }.getOrNull()?.lowercase(Locale.ROOT)

        val queryMime = queryValue(setOf("mime", "type", "content_type")).orEmpty()
        val queryFormat = queryValue(setOf("format", "output", "container", "stream_type", "type")).orEmpty()
        val effectiveMime = mime.ifBlank { queryMime }
        val isHls = extension == "m3u8" ||
            queryFormat in setOf("m3u8", "hls", "application/x-mpegurl", "application/vnd.apple.mpegurl") ||
            effectiveMime.contains("mpegurl")
        val isDash = extension == "mpd" ||
            queryFormat in setOf("mpd", "dash", "application/dash+xml") ||
            effectiveMime.contains("dash+xml")

        val kind = when {
            isHls -> WebMediaKind.HLS
            isDash -> WebMediaKind.DASH
            extension in audioExtensions || effectiveMime.startsWith("audio/") -> WebMediaKind.AUDIO_FILE
            extension in videoExtensions || effectiveMime.startsWith("video/") -> WebMediaKind.VIDEO_FILE
            mediaElement.equals("audio", ignoreCase = true) -> WebMediaKind.MEDIA_ELEMENT
            mediaElement.equals("video", ignoreCase = true) -> WebMediaKind.MEDIA_ELEMENT
            else -> return null
        }

        val inferredMime = when {
            kind == WebMediaKind.HLS -> "application/x-mpegURL"
            kind == WebMediaKind.DASH -> "application/dash+xml"
            effectiveMime.isNotBlank() -> effectiveMime
            kind == WebMediaKind.AUDIO_FILE -> when (extension) {
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "opus" -> "audio/opus"
                else -> "audio/ogg"
            }
            kind == WebMediaKind.VIDEO_FILE -> when (extension) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "ogv" -> "video/ogg"
                "mkv" -> "video/x-matroska"
                else -> null
            }
            // Do not invent an audio/video MIME for opaque URLs; that can cause HLS/DASH
            // manifests to be misrouted into MediaExtractor as ordinary MP4 files.
            else -> null
        }

        return WebMediaCandidate(
            url = uri.normalize().toASCIIString(),
            kind = kind,
            mimeType = inferredMime,
            pageUrl = pageUrl,
        )
    }

    /** Only browser/session headers useful for fetching the exact candidate are forwarded. */
    fun safeHeaders(input: Map<String, String>): Map<String, String> =
        input.entries.mapNotNull { (name, value) ->
            val normalized = name.trim().lowercase(Locale.ROOT)
            if (normalized !in forwardableHeaders || value.isBlank() ||
                value.any { it == '\r' || it == '\n' || it == '\u0000' }
            ) null else name.trim() to value
        }.toMap()

    fun normalizePageUrl(value: String): String? {
        val input = value.trim()
        if (input.isBlank()) return null
        val normalized = if (input.startsWith("http://", true) || input.startsWith("https://", true)) {
            input
        } else {
            "https://$input"
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if ((!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) return null
        return uri.normalize().toASCIIString()
    }

    fun looksLikeHls(url: String): Boolean = classify(url)?.kind == WebMediaKind.HLS
    fun looksLikeDash(url: String): Boolean = classify(url)?.kind == WebMediaKind.DASH
}
