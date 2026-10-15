package io.translatetube.local.web

import java.net.URI
import java.util.Locale

/** URL checks for pages opened in the in-app web video picker and their HTML5 media sources. */
internal object WebMediaUrlPolicy {
    private val explicitSchemePattern = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")
    // IPv6 literals need brackets; accept only characters used by literal address/zone forms.
    private val hostPortPattern = Regex("""^(?:\[[0-9A-Fa-f:.%]+\]|[^:/?#]+):\d+(?:[/?#].*)?$""")
    private val blockedSchemes = setOf("javascript", "file", "data", "blob", "content", "intent", "mailto", "tel", "about")

    private fun containsControlCharacters(value: String): Boolean =
        value.any { Character.isISOControl(it) }

    /** Reject unusable authorities early; java.net.URI accepts out-of-range numeric ports. */
    private fun isValidHttpUri(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val authority = uri.rawAuthority.orEmpty()
        val port = uri.port
        return scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            !authority.endsWith(":") &&
            (port == -1 || port in 1..65535)
    }
    fun normalizePageUrl(input: String): String? {
        // Do not let trim() erase control characters and change the URL being validated.
        if (containsControlCharacters(input)) return null
        val raw = input.trim()
        if (raw.isEmpty()) return null
        // Reject known non-web schemes before treating a colon and digits as a host port.
        // Otherwise e.g. javascript:8080/alert could be silently reinterpreted as an HTTPS host.
        val detectedScheme = explicitSchemePattern.find(raw)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        if (detectedScheme in blockedSchemes) return null
        // A colon followed by digits can be a host port (example.com:8080), not a URI scheme.
        val hostPortWithoutScheme = hostPortPattern.matches(raw)
        val explicitScheme = if (hostPortWithoutScheme) null else detectedScheme
        if (explicitScheme != null && explicitScheme !in setOf("http", "https")) return null
        val normalized = when {
            raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (!isValidHttpUri(uri)) return null
        return uri.toASCIIString()
    }

    /** Network-observed media resources, including videos inside cross-origin iframes. */
    fun looksLikeMediaRequest(input: String): Boolean {
        if (containsControlCharacters(input)) return false
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return false
        if (!isValidHttpUri(uri)) return false
        val path = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
        return listOf(".mp4", ".webm", ".m4v", ".mov", ".mkv", ".mp3", ".m4a", ".aac", ".ogg", ".m3u8", ".mpd")
            .any(path::endsWith)
    }

    fun normalizeMediaUrl(input: String): String? {
        if (containsControlCharacters(input)) return null
        val raw = input.trim()
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!isValidHttpUri(uri)) return null
        if (unsupportedReason(raw) != null) return null
        return uri.toASCIIString()
    }

    fun unsupportedReason(input: String): String? {
        if (containsControlCharacters(input)) return "媒体地址包含无效控制字符。"
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
        val path = runCatching { URI(raw).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (path.endsWith(".mpd")) {
            return "检测到 DASH 分片清单；当前版本暂不支持 DASH 音轨缓存。"
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || !isValidHttpUri(uri)) {
            return "未检测到可直接访问的 HTTP/HTTPS 媒体地址。"
        }
        return null
    }
}
