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

    fun acceptsMediaUrl(url: String): Boolean =
        url.isNotBlank() && url != "null" && url.length <= MAX_MEDIA_URL_CHARS

    fun normalizeTitle(value: String): String = value.take(MAX_TITLE_CHARS)

    fun normalizePoster(value: String): String = value.take(MAX_POSTER_CHARS)

    fun mergeCandidates(
        existing: List<WebVideoCandidate>,
        incoming: List<WebVideoCandidate>,
    ): List<WebVideoCandidate> =
        (existing + incoming)
            .groupBy { it.url }
            .map { (_, group) ->
                val first = group.first()
                first.copy(
                    title = group.firstOrNull { it.title.isNotBlank() }?.title.orEmpty(),
                    poster = group.firstOrNull { it.poster.isNotBlank() }?.poster.orEmpty(),
                    drmProtected = group.any { it.drmProtected },
                    durationSeconds = group.maxOfOrNull { it.durationSeconds } ?: first.durationSeconds,
                )
            }
            .take(MAX_RETAINED_CANDIDATES)
}
