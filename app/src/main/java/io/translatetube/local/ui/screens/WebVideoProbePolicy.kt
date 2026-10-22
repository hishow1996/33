package io.translatetube.local.ui.screens

/** Resource limits for JavaScript-reported media candidates in the in-app WebView. */
internal object WebVideoProbePolicy {

    /**
     * 候选展示优先级：m3u8/mpd 流清单（网络 hook 从接口响应里抓到的真实流）最可靠，
     * 排最前让用户一眼可辨；广告类直链文件最后。稳定排序保持同优先级的新地址在前。
     */
    fun candidateRank(url: String): Int =
        when {
            url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                url.substringBefore('?').endsWith(".mpd", ignoreCase = true) -> 100
            else -> 0
        }
    const val MAX_PAYLOAD_CHARS = 2 * 1024 * 1024
    const val MAX_ROWS_PER_PAYLOAD = 120
    const val MAX_RETAINED_CANDIDATES = 200
    const val MAX_MEDIA_URL_CHARS = 8 * 1024
    const val MAX_TITLE_CHARS = 512
    const val MAX_POSTER_CHARS = 2048

    /**
     * Identify the same Bilibili video across short-link redirects and tracking-query changes.
     * Page number is part of the identity, because a different "p" is a different media segment.
     */
    fun isSameBilibiliVideoPage(firstUrl: String, secondUrl: String): Boolean {
        val first = bilibiliVideoIdentity(firstUrl) ?: return false
        return first == bilibiliVideoIdentity(secondUrl)
    }

    fun isDuplicateCompletedNavigation(
        targetUrl: String,
        currentPageUrl: String,
        lastCompletedUrl: String?,
    ): Boolean {
        if (lastCompletedUrl.isNullOrBlank()) return false
        return isSameBilibiliVideoPage(targetUrl, currentPageUrl) &&
            isSameBilibiliVideoPage(targetUrl, lastCompletedUrl)
    }

    private fun bilibiliVideoIdentity(rawUrl: String): String? {
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (scheme !in setOf("http", "https") ||
            !(host == "b23.tv" || host == "bilibili.com" || host.endsWith(".bilibili.com"))
        ) return null

        val path = uri.rawPath.orEmpty()
        val match = Regex(
            "/video/(BV[0-9A-Za-z]+|av[0-9]+)(?:/|$)",
            RegexOption.IGNORE_CASE,
        ).find(path) ?: return null
        val rawId = match.groupValues[1]
        // BVID characters are case-sensitive; only normalize its BV prefix.
        val videoId = if (rawId.startsWith("BV", ignoreCase = true)) {
            "BV" + rawId.substring(2)
        } else {
            rawId.lowercase(java.util.Locale.ROOT)
        }
        val pageNumber = uri.rawQuery.orEmpty()
            .split("&")
            .firstOrNull { it.substringBefore("=") == "p" }
            ?.substringAfter("=", "")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
        return "$videoId:p=$pageNumber"
    }

    /**
     * yt-dlp is the last automatic fallback, not the first parser. Start it only after the
     * WebView has finished loading, candidate validation has had time to run, and no usable
     * HTTP(S) media URL was verified by the existing parser.
     */
    fun shouldStartAutomaticYtDlp(
        pageLoaded: Boolean,
        isEligible: Boolean,
        isPikPakShare: Boolean,
        isBusy: Boolean,
        autoAttempts: Int,
        hasUsableCandidate: Boolean,
    ): Boolean = pageLoaded && isEligible && !isPikPakShare &&
        !isBusy && autoAttempts == 0 && !hasUsableCandidate

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
        val normalizedPath = uri.rawPath.orEmpty().lowercase(java.util.Locale.ROOT)
        val fileName = normalizedPath.substringAfterLast('/')
        // These are site probe/beacon assets, not episode media. YFSP currently exposes a
        // static /media/test.mp4 alongside the real CDN stream; selecting it makes Media3
        // report ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED.
        if (fileName == "scan_qr.mp3") return false
        if (fileName == "test.mp4" && normalizedPath.contains("/static/media/")) return false

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
            .sortedByDescending { candidateRank(it.url) }
            .take(MAX_RETAINED_CANDIDATES)
}



