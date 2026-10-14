package io.translatetube.local.web

import java.net.URI

/** URL checks for pages opened in the in-app web video picker and their HTML5 media sources. */
internal object WebMediaUrlPolicy {
    fun normalizePageUrl(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(raw)?.value
        if (explicitScheme != null &&
            !raw.startsWith("https://", ignoreCase = true) &&
            !raw.startsWith("http://", ignoreCase = true)
        ) return null
        val normalized = when {
            raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toASCIIString()
    }

    /** Network-observed media resources, including videos inside cross-origin iframes. */
    fun looksLikeMediaRequest(input: String): Boolean {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        val path = uri.rawPath.orEmpty().lowercase()
        return listOf(".mp4", ".webm", ".m4v", ".mov", ".mkv", ".mp3", ".m4a", ".aac", ".ogg", ".m3u8", ".mpd")
            .any(path::endsWith)
    }

    fun normalizeMediaUrl(input: String): String? {
        val raw = input.trim()
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) return null
        if (unsupportedReason(raw) != null) return null
        return uri.toASCIIString()
    }

    fun unsupportedReason(input: String): String? {
        val raw = input.trim()
        if (raw.startsWith("blob:", ignoreCase = true)) {
            return "此网站通过 blob/MSE 临时媒体流播放，无法直接读取独立音轨。"
        }
        if (raw.startsWith("data:", ignoreCase = true) ||
            raw.startsWith("javascript:", ignoreCase = true) ||
            raw.startsWith("file:", ignoreCase = true)
        ) {
            return "该媒体地址不是可独立访问的 HTTP/HTTPS 视频流。"
        }
        val path = runCatching { URI(raw).rawPath.orEmpty().lowercase() }.getOrDefault("")
        if (path.endsWith(".mpd")) {
            return "检测到 DASH 分片清单；当前版本暂不支持 DASH 音轨缓存。"
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) {
            return "未检测到可直接访问的 HTTP/HTTPS 媒体地址。"
        }
        return null
    }
}
