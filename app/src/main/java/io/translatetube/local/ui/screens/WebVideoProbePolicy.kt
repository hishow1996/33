package io.translatetube.local.ui.screens

/** Resource limits for JavaScript-reported media candidates in the in-app WebView. */
internal object WebVideoProbePolicy {
    const val MAX_PAYLOAD_CHARS = 2 * 1024 * 1024
    const val MAX_ROWS_PER_PAYLOAD = 120
    const val MAX_RETAINED_CANDIDATES = 200
    const val MAX_MEDIA_URL_CHARS = 8 * 1024
    const val MAX_TITLE_CHARS = 512
    const val MAX_POSTER_CHARS = 2048

    fun acceptsPayload(payload: String): Boolean =
        payload.length in 2..MAX_PAYLOAD_CHARS

    fun acceptsMediaUrl(url: String): Boolean {
        // The page bridge is untrusted input. Reject controls before trim() so a trailing
        // newline cannot be normalized into a different, accepted media URL.
        if (url.isBlank() || url.any { Character.isISOControl(it) }) return false
        val raw = url.trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) ||
            raw.length > MAX_MEDIA_URL_CHARS ||
            raw.any { it.code <= 0x20 || it.code == 0x7f }
        ) return false

        // Keep blob candidates so the UI can explain unsupported MSE streams, but reject
        // javascript:, data:, file:, intent:, embedded credentials, and malformed ports.
        if (raw.startsWith("blob:", ignoreCase = true)) {
            val inner = runCatching { java.net.URI(raw.substring(5)) }.getOrNull() ?: return false
            return isSafeHttpUri(inner)
        }
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
        if (!isSafeHttpUri(uri)) return false

        // Some sites place a QR/ad beacon in a hidden HTML5 audio element. It is not the
        // programme audio and commonly returns JSON/HTML when reopened from the media picker.
        val fileName = uri.rawPath.orEmpty().substringAfterLast('/').lowercase(java.util.Locale.ROOT)
        if (fileName == "scan_qr.mp3") return false

        return true
    }

    private fun isSafeHttpUri(uri: java.net.URI): Boolean {
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        val authority = uri.rawAuthority.orEmpty()
        return scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            authority.isNotEmpty() &&
            !authority.endsWith(":") &&
            (uri.port == -1 || uri.port in 1..65535)
    }

    fun normalizeTitle(value: String): String =
        value.filterNot { Character.isISOControl(it) && !it.isWhitespace() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TITLE_CHARS)

    /** Page-controlled poster values may only cause remote HTTP(S) images to be loaded. */
    fun normalizePoster(value: String): String {
        if (value.isBlank() || value.any { Character.isISOControl(it) }) return ""
        val raw = value.trim()
        if (raw.isEmpty() || raw.length > MAX_POSTER_CHARS ||
            raw.any { it.code <= 0x20 || it.code == 0x7f }
        ) return ""
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return ""
        if (!isSafeHttpUri(uri)) return ""
        return raw
    }

    fun mergeCandidates(
        existing: List<WebVideoCandidate>,
        incoming: List<WebVideoCandidate>,
    ): List<WebVideoCandidate> =
        // New observations come first: a fresh stream discovered after the list reaches its cap
        // must not be hidden forever behind the oldest 200 candidates.
        (incoming + existing)
            .filter { acceptsMediaUrl(it.url) }
            .groupBy { it.url }
            .map { (_, group) ->
                val latest = group.first()
                val retainedDuration = group.maxOfOrNull { it.durationSeconds }
                    ?.takeIf { it.isFinite() && it > 0.0 }
                latest.copy(
                    title = latest.title.ifBlank {
                        group.firstOrNull { it.title.isNotBlank() }?.title.orEmpty()
                    },
                    poster = latest.poster.ifBlank {
                        group.firstOrNull { it.poster.isNotBlank() }?.poster.orEmpty()
                    },
                    drmProtected = group.any { it.drmProtected },
                    durationSeconds = latest.durationSeconds
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?: retainedDuration
                        ?: 0.0,
                )
            }
            .take(MAX_RETAINED_CANDIDATES)
}
