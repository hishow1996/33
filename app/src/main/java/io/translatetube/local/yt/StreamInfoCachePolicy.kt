package io.translatetube.local.yt

import java.io.IOException

/** Testable freshness, retry and publication rules for signed YouTube stream metadata. */
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

    /** Only retry transient transport failures; invalid video/unsupported-content errors are final. */
    fun isRetryableFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is IOException) return true
            cause = cause.cause
            depth++
        }
        return false
    }
}
