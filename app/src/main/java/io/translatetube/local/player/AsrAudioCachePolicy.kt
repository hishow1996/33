package io.translatetube.local.player

/** Pure policy for keeping retry audio small, bounded and short-lived. */
internal object AsrAudioCachePolicy {
    const val MAX_AUDIO_BYTES = 512L * 1024L * 1024L
    const val CACHE_BUDGET_BYTES = 768L * 1024L * 1024L
    const val TTL_MS = 7L * 24L * 60L * 60L * 1_000L

    fun canReuse(
        isFile: Boolean,
        sizeBytes: Long,
        lastModifiedMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!isFile || sizeBytes !in 1L..MAX_AUDIO_BYTES) return false
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false
        return nowMs - lastModifiedMs <= TTL_MS
    }

    /** Unknown content length is allowed, but a known length must match the received bytes. */
    fun isCompleteDownload(expectedBytes: Long, actualBytes: Long): Boolean =
        actualBytes in 1L..MAX_AUDIO_BYTES &&
            (expectedBytes <= 0L || expectedBytes == actualBytes)
}
