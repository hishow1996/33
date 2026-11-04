package io.translatetube.local.player

import java.net.URI
import java.util.Locale

/**
 * Validates playback URLs and keeps browser request metadata from breaking Media3's own
 * range-based reads. Only page/session headers that are useful for a media GET are retained.
 */
internal object PlaybackHttpUrlPolicy {
    private val allowedHeaders = mapOf(
        "cookie" to "Cookie",
        "referer" to "Referer",
        "origin" to "Origin",
        "user-agent" to "User-Agent",
        "accept" to "Accept",
        "accept-language" to "Accept-Language",
    )

    fun isValidMediaUrl(value: String): Boolean {
        // Validate the unmodified input first so trim() cannot hide a leading/trailing
        // newline, tab, DEL or C1 control character.
        if (value.isBlank() || value.any { Character.isISOControl(it) }) return false
        val raw = value.trim()
        if (raw.isEmpty() || raw.any { it.code <= 0x20 || it.code == 0x7f }) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val authority = uri.rawAuthority.orEmpty()
        val port = uri.port
        return scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            authority.isNotEmpty() &&
            !authority.endsWith(":") &&
            (port == -1 || port in 1..65535)
    }

    /**
     * Range is owned by Media3 and changes on every read. Replaying the WebView's old Range
     * header can make the player request only a stale byte window. Hop-by-hop headers and
     * arbitrary headers are also not safe as persistent default properties.
     */
    fun sanitizeRequestHeaders(headers: Map<String, String>): Map<String, String> {
        val safe = linkedMapOf<String, String>()
        headers.forEach { (rawName, rawValue) ->
            if (rawName.isBlank() || rawName != rawName.trim()) return@forEach
            val name = rawName.lowercase(Locale.ROOT)
            val canonicalName = allowedHeaders[name] ?: return@forEach
            if (rawValue.length > 8 * 1024 ||
                rawValue.any { Character.isISOControl(it) }
            ) return@forEach
            val value = rawValue.trim()
            if (value.isEmpty()) return@forEach
            if ((name == "referer" || name == "origin") && !isValidMediaUrl(value)) {
                return@forEach
            }
            safe[canonicalName] = value
        }
        return safe
    }
}
