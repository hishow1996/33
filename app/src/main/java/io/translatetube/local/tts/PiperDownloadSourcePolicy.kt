package io.translatetube.local.tts

/** Official-first model source order with known mirrors as network fallbacks. */
internal object PiperDownloadSourcePolicy {
    fun urls(officialUrl: String): List<String> {
        val url = officialUrl.trim()
        if (url.isEmpty()) return emptyList()
        if (!url.startsWith("https://github.com/", ignoreCase = true)) return listOf(url)
        return listOf(
            url,
            "https://ghfast.top/$url",
            "https://gh-proxy.com/$url",
        ).distinct()
    }

    /** A missing upstream digest is tolerated; a published digest must match exactly. */
    fun checksumMatches(expected: String?, actual: String?): Boolean =
        expected.isNullOrBlank() ||
            (!actual.isNullOrBlank() &&
                expected.trim().removePrefix("sha256:").equals(actual, ignoreCase = true))
}
