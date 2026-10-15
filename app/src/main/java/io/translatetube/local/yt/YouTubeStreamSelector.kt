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
    private const val DEFAULT_HEIGHT = 360

    fun isHttpUrl(value: String): Boolean {
        // Check the original string before trim(): trimming a newline can silently turn an
        // invalid stream URL into a different, accepted URL.
        if (value.isBlank() || value.any { Character.isISOControl(it) }) return false
        val trimmed = value.trim()
        return (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)) &&
            runCatching {
                val uri = java.net.URI(trimmed)
                !uri.host.isNullOrBlank() && uri.userInfo == null &&
                    !uri.rawAuthority.orEmpty().endsWith(":") &&
                    (uri.port == -1 || uri.port in 1..65535)
            }.getOrDefault(false)
    }

    /** Prefer muxed MP4, other muxed formats, adaptive MP4, then other adaptive formats. */
    fun chooseVideoStreams(
        input: List<YouTubeVideoCandidate>,
        separateAudioAvailable: Boolean = true,
    ): List<YouTubeVideoCandidate> =
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
            // Without a valid separate audio track, only muxed streams are playable with sound.
            .filter { separateAudioAvailable || !it.videoOnly }

    fun defaultQualityIndex(candidates: List<YouTubeVideoCandidate>): Int {
        if (candidates.isEmpty()) return -1
        // Match PlayerQualityPolicy: prefer the highest valid quality at or below 360p.
        // Only choose a higher resolution when every available stream is above 360p.
        return candidates.indices
            .filter { candidates[it].height <= DEFAULT_HEIGHT }
            .maxByOrNull { candidates[it].height }
            ?: candidates.indices.minByOrNull { candidates[it].height }
            ?: -1
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

    /**
     * ASR does not need the highest fidelity stream. Preserve M4A compatibility preference,
     * but choose the lowest known bitrate inside that tier to reduce download time and cache use.
     * The returned index keeps the selected metadata tied to its original NewPipe AudioStream.
     */
    fun chooseAudioStreamForAsrIndex(input: List<YouTubeAudioCandidate>): Int {
        val valid = input.indices.filter { isHttpUrl(input[it].url) }
        if (valid.isEmpty()) return -1
        val m4a = valid.filter {
            input[it].formatSuffix?.equals("m4a", ignoreCase = true) == true
        }
        val candidates = m4a.ifEmpty { valid }
        return candidates.minWithOrNull(
            compareBy<Int> {
                input[it].bitrate.takeIf { rate -> rate > 0 } ?: Int.MAX_VALUE
            }.thenBy { input[it].url }
        ) ?: -1
    }


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
