package io.translatetube.local.yt

import java.net.URI
import java.util.Locale

/** Safe URL and format-selection rules for resolving a single playable video from yt-dlp metadata. */
internal data class YtDlpFormatCandidate(
    val url: String,
    val extension: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val bitrate: Double,
)

internal object YtDlpMediaPolicy {
    fun isSafeHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }

    fun isHlsCandidate(url: String, extension: String?): Boolean {
        if (!isSafeHttpUrl(url)) return false
        if (extension.equals("m3u8", ignoreCase = true)) return true
        val path = runCatching { URI(url.trim()).rawPath.orEmpty() }.getOrDefault("")
        return path.endsWith(".m3u8", ignoreCase = true)
    }

    /**
     * Prefer a combined MP4 stream for older Android decoders, then other combined formats,
     * then a valid audio+video HLS manifest. Audio-only formats are never playback candidates.
     * A direct info URL is a last resort only when yt-dlp supplied no format metadata at all.
     */
    fun selectPlayableUrl(
        formats: List<YtDlpFormatCandidate>,
        fallbackUrl: String?,
    ): String? {
        val combined = formats.asSequence()
            .filter { it.hasVideo && it.hasAudio && isSafeHttpUrl(it.url) }
            .filterNot { isHlsCandidate(it.url, it.extension) }
            .minWithOrNull(
                compareBy<YtDlpFormatCandidate> { compatibilityRank(it.extension) }
                    .thenByDescending { it.bitrate.takeIf { rate -> rate.isFinite() } ?: 0.0 }
                    .thenBy { it.url }
            )
        if (combined != null) return combined.url

        val hls = formats.asSequence()
            .filter { it.hasVideo && it.hasAudio && isHlsCandidate(it.url, it.extension) }
            .maxByOrNull { it.bitrate.takeIf { rate -> rate.isFinite() } ?: 0.0 }
        if (hls != null) return hls.url

        if (formats.isEmpty()) return fallbackUrl?.takeIf(::isSafeHttpUrl)
        return null
    }

    private fun compatibilityRank(extension: String?): Int = when (extension?.lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> 0
        "webm" -> 1
        "mov", "mkv" -> 2
        else -> 3
    }
}
