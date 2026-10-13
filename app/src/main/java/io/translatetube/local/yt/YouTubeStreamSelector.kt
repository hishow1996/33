package io.translatetube.local.yt

/**
 * Normalized stream metadata for compatible, deterministic YouTube quality selection.
 * This file intentionally has no Android/NewPipe dependency so selection rules can be tested.
 */
internal data class YouTubeVideoCandidate(
    val height: Int,
    val url: String,
    val videoOnly: Boolean,
    val formatSuffix: String?,
    val bitrate: Int,
)

internal data class YouTubeAudioCandidate(
    val url: String,
    val formatSuffix: String?,
    val bitrate: Int,
)

internal object YouTubeStreamSelector {
    fun isHttpUrl(value: String): Boolean {
        val trimmed = value.trim()
        return (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)) &&
            runCatching {
                val uri = java.net.URI(trimmed)
                !uri.host.isNullOrBlank() && uri.userInfo == null
            }.getOrDefault(false)
    }

    /** Prefer muxed MP4, other muxed formats, adaptive MP4, then other adaptive formats. */
    fun chooseVideoStreams(input: List<YouTubeVideoCandidate>): List<YouTubeVideoCandidate> =
        input.asSequence()
            .filter { it.height in 1..2160 && isHttpUrl(it.url) }
            .groupBy { it.height }
            .mapNotNull { (_, sameHeight) ->
                sameHeight.minWithOrNull(
                    compareBy<YouTubeVideoCandidate> { compatibilityRank(it) }
                        .thenByDescending { it.bitrate.coerceAtLeast(0) }
                        .thenBy { it.url }
                )
            }
            .sortedByDescending { it.height }

    fun defaultQualityIndex(candidates: List<YouTubeVideoCandidate>): Int {
        if (candidates.isEmpty()) return -1
        val exact = candidates.indexOfFirst { it.height == 360 }
        if (exact >= 0) return exact
        // candidates are sorted high-to-low, so equal-distance ties choose the higher quality.
        return candidates.indices.minByOrNull { kotlin.math.abs(candidates[it].height - 360) } ?: 0
    }

    /** Prefer M4A/AAC for broad decoder compatibility, then highest bitrate in that tier. */
    fun chooseAudioStream(input: List<YouTubeAudioCandidate>): YouTubeAudioCandidate? =
        input.asSequence()
            .filter { isHttpUrl(it.url) }
            .sortedWith(
                compareBy<YouTubeAudioCandidate> { audioCompatibilityRank(it) }
                    .thenByDescending { it.bitrate.coerceAtLeast(0) }
                    .thenBy { it.url }
            )
            .firstOrNull()

    private fun compatibilityRank(candidate: YouTubeVideoCandidate): Int {
        val mp4 = candidate.formatSuffix?.equals("mp4", ignoreCase = true) == true
        return when {
            !candidate.videoOnly && mp4 -> 0
            !candidate.videoOnly -> 1
            mp4 -> 2
            else -> 3
        }
    }

    private fun audioCompatibilityRank(candidate: YouTubeAudioCandidate): Int =
        if (candidate.formatSuffix?.equals("m4a", ignoreCase = true) == true ||
            candidate.formatSuffix?.equals("mp4", ignoreCase = true) == true
        ) 0 else 1
}
