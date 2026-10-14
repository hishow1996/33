package io.translatetube.local.yt

/** Testable freshness and publication rules for signed YouTube stream metadata. */
internal object StreamInfoCachePolicy {
    const val TTL_MS = 5L * 60L * 1_000L

    fun isFresh(cachedAtMs: Long, nowMs: Long): Boolean =
        cachedAtMs > 0L && nowMs >= cachedAtMs && nowMs - cachedAtMs <= TTL_MS

    fun mayPublish(
        requestGeneration: Long,
        currentRequestGeneration: Long,
        invalidationGeneration: Long,
        currentInvalidationGeneration: Long,
    ): Boolean =
        requestGeneration == currentRequestGeneration &&
            invalidationGeneration == currentInvalidationGeneration
}
