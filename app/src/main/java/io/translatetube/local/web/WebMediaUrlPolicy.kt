package io.translatetube.local.web

import java.net.IDN
import java.net.URI
import java.util.Locale

/** URL checks for pages opened in the in-app web video picker and their HTML5 media sources. */
internal object WebMediaUrlPolicy {
    private val explicitSchemePattern = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")
    // IPv6 literals need brackets; accept only characters used by literal address/zone forms.
    private val hostPortPattern = Regex("""^(?:\[[0-9A-Fa-f:.%]+\]|[^:/?#]+):\d+(?:[/?#].*)?$""")
    private val blockedSchemes = setOf("javascript", "file", "data", "blob", "content", "intent", "mailto", "tel", "about")
    // Some apps wrap copied links with zero-width formatting characters. Ignore them only at
    // the edges; an embedded one remains suspicious and is rejected.
    private val boundaryFormattingChars = setOf('\u200B', '\uFEFF', '\u2060', '\u00AD')

    private fun containsControlCharacters(value: String): Boolean =
        value.any { Character.isISOControl(it) }

    private fun isBoundaryNoise(char: Char): Boolean =
        char.isWhitespace() || Character.isSpaceChar(char) || char in boundaryFormattingChars

    private fun trimUrlEdges(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isBoundaryNoise(value[start])) start++
        while (end > start && isBoundaryNoise(value[end - 1])) end--
        return value.substring(start, end)
    }

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

    /**
     * Parse like a browser for ordinary pasted page URLs:
     * - encode literal spaces outside the authority;
     * - convert internationalized host names to IDN ASCII form when URI.host is unavailable.
     * Scheme, authority, credentials and port are still validated strictly afterwards.
     */
    private fun parsePageUri(value: String): URI? {
        val schemeDelimiter = value.indexOf("://")
        if (schemeDelimiter <= 0) return null

        val prefix = value.substring(0, schemeDelimiter + 3)
        val remainder = value.substring(schemeDelimiter + 3)
        val authorityEnd = remainder.indexOfAny(charArrayOf('/', '?', '#'))
            .let { if (it < 0) remainder.length else it }
        val authority = remainder.substring(0, authorityEnd)
        if (authority.isBlank()) return null
        val suffix = remainder.substring(authorityEnd).replace(" ", "%20")

        val direct = runCatching { URI(prefix + authority + suffix) }.getOrNull()
        if (direct != null && !direct.host.isNullOrBlank()) return direct
        // Never allow a user-info component as a fallback for an otherwise malformed authority.
        if (authority.contains('@')) return null

        val asciiAuthority = normalizeAuthorityHost(authority) ?: return null
        return runCatching { URI(prefix + asciiAuthority + suffix) }.getOrNull()
    }

    private fun normalizeAuthorityHost(authority: String): String? {
        if (authority.startsWith("[")) {
            // Preserve bracketed IPv6; URI performs the final syntax and port checks.
            val closeBracket = authority.indexOf(']')
            if (closeBracket <= 1) return null
            val portSuffix = authority.substring(closeBracket + 1)
            if (portSuffix.isNotEmpty() && !Regex("""^:\d+$""").matches(portSuffix)) return null
            return authority
        }

        val colon = authority.lastIndexOf(':')
        val host: String
        val portSuffix: String
        if (colon >= 0) {
            // DNS names have at most one unbracketed colon, and it must introduce a numeric port.
            if (authority.indexOf(':') != colon) return null
            val port = authority.substring(colon + 1)
            if (port.isEmpty() || !port.all { it in '0'..'9' }) return null
            host = authority.substring(0, colon)
            portSuffix = authority.substring(colon)
        } else {
            host = authority
            portSuffix = ""
        }
        if (host.isBlank()) return null
        val asciiHost = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
            ?: return null
        if (asciiHost.isBlank() || asciiHost.length > 253) return null
        return asciiHost + portSuffix
    }

    fun normalizePageUrl(input: String): String? {
        // Reject embedded controls before trimming. Boundary spaces/format marks are handled below.
        if (containsControlCharacters(input)) return null
        val raw = trimUrlEdges(input)
        if (raw.isEmpty() || raw.any { it in boundaryFormattingChars }) return null
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
        val uri = parsePageUri(normalized) ?: return null
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

    /**
     * Returns a normalized direct media URL only when the URL path clearly identifies a
     * media resource. Ordinary pages must keep using WebView probing rather than bypassing it.
     */
    fun normalizeDirectMediaUrl(input: String): String? {
        val normalized = normalizeMediaUrl(input) ?: return null
        return normalized.takeIf { looksLikeMediaRequest(it) }
    }

    /**
     * Only explicit audio-file extensions may use a full-response fallback. Video containers
     * must never silently fall back to downloading an entire video just to obtain its soundtrack.
     */
    fun isExplicitAudioOnlyResource(input: String): Boolean {
        if (containsControlCharacters(input)) return false
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return false
        if (!isValidHttpUri(uri)) return false
        val path = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
        return listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac", ".mpga")
            .any(path::endsWith)
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
        val parsedPath = runCatching { URI(raw).rawPath.orEmpty() }.getOrDefault("")
        val path = parsedPath.lowercase(Locale.ROOT)
        if (path.endsWith(".mpd")) {
            return "检测到 DASH 分片清单；当前版本暂不支持 DASH 音轨缓存。"
        }
        // 爱壹帆的 /static/media/test.mp4 是网页探测/占位文件，并非正片；
        // 其响应可能是空载荷或页面内容，Media3 无法识别容器。筛选入口与最终播放入口都拦截。
        if (path.endsWith("/static/media/test.mp4") &&
            runCatching { URI(raw).host.equals("m.yfsp.tv", ignoreCase = true) }.getOrDefault(false)
        ) {
            return "这是爱壹帆网页的测试占位视频，不是正片资源。请在候选列表中选择其他 CDN 视频地址。"
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || !isValidHttpUri(uri)) {
            return "未检测到可直接访问的 HTTP/HTTPS 媒体地址。"
        }
        return null
    }
}
