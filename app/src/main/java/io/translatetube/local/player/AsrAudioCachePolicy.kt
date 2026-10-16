package io.translatetube.local.player

/** Pure policy for keeping retry audio small, bounded and short-lived. */
internal object AsrAudioCachePolicy {
    const val MAX_AUDIO_BYTES = 160L * 1024L * 1024L
    const val CACHE_BUDGET_BYTES = 160L * 1024L * 1024L
    const val TTL_MS = 7L * 24L * 60L * 60L * 1_000L
    const val PARTIAL_TTL_MS = 6L * 60L * 60L * 1_000L

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

    /** Checks a future write before addition so untrusted sample sizes cannot overflow Long. */
    fun canAppendBytes(currentBytes: Long, appendBytes: Long): Boolean =
        currentBytes in 0L..MAX_AUDIO_BYTES &&
            appendBytes >= 0L &&
            appendBytes <= MAX_AUDIO_BYTES - currentBytes

    /** Only -1 means unknown to OkHttp; other negative lengths are invalid metadata. */
    fun isCompleteDownload(expectedBytes: Long, actualBytes: Long): Boolean =
        actualBytes in 1L..MAX_AUDIO_BYTES &&
            (expectedBytes == -1L || expectedBytes == actualBytes)

    /** An abandoned sibling .part file is removable only after the stale-file grace period. */
    fun isStalePartial(lastModifiedMs: Long, nowMs: Long): Boolean =
        lastModifiedMs > 0L && nowMs >= lastModifiedMs &&
            nowMs - lastModifiedMs > PARTIAL_TTL_MS
}
