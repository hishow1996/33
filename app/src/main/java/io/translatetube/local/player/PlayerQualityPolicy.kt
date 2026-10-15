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
        val valid = streams.filter { (url, height) -> height > 0 && isValidUrl(url) }
        return valid.filter { it.second <= preferredHeight }.maxByOrNull { it.second }
            ?: valid.minByOrNull { it.second }
    }
}
