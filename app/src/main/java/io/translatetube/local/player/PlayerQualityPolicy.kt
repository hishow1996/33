package io.translatetube.local.player

/**
 * Choose a safe default for older phones: use 360p when available, otherwise the highest
 * valid resolution below it. Only use a higher resolution when no lower stream is usable.
 */
internal object PlayerQualityPolicy {
    const val DEFAULT_HEIGHT = 360

    fun chooseDefault(
        streams: List<Pair<String, Int>>,
        isValidUrl: (String) -> Boolean,
        preferredHeight: Int = DEFAULT_HEIGHT,
    ): Pair<String, Int>? {
        require(preferredHeight > 0) { "Preferred video height must be positive" }
        val valid = streams.filter { (url, height) ->
            height > 0 && isCandidateUrlValid(url, isValidUrl)
        }
        return valid.filter { it.second <= preferredHeight }.maxByOrNull { it.second }
            ?: valid.minByOrNull { it.second }
    }

    /** Preflight legacy source switching so an unusable result cannot stop the current video. */
    fun hasUsableSourceCandidate(
        muxedUrl: String?,
        adaptiveVideoUrl: String?,
        audioUrl: String,
        isValidUrl: (String) -> Boolean,
    ): Boolean {
        val hasMuxed = isCandidateUrlValid(muxedUrl, isValidUrl)
        val hasAdaptive = isCandidateUrlValid(adaptiveVideoUrl, isValidUrl) &&
            isCandidateUrlValid(audioUrl, isValidUrl)
        return hasMuxed || hasAdaptive
    }

    /** A malformed extractor candidate must be skipped rather than aborting all quality discovery. */
    private fun isCandidateUrlValid(
        url: String?,
        isValidUrl: (String) -> Boolean,
    ): Boolean {
        if (url.isNullOrBlank() || url.any { Character.isISOControl(it) }) return false
        return try {
            isValidUrl(url.trim())
        } catch (_: Exception) {
            false
        }
    }
}
