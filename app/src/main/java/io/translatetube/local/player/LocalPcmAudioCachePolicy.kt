package io.translatetube.local.player

/** Validation and storage bounds for reusable local-video ASR PCM files. */
internal object LocalPcmAudioCachePolicy {
    const val TTL_MS = 7L * 24L * 60L * 60L * 1_000L
    const val PARTIAL_TTL_MS = 6L * 60L * 60L * 1_000L
    const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    const val MAX_CACHE_FILES = 6

    /** PCM16 mono payloads must be non-empty, even-sized and within the decoder's safety limit. */
    fun canReuse(
        isFile: Boolean,
        sizeBytes: Long,
        lastModifiedMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!isFile || sizeBytes <= 0L || sizeBytes % 2L != 0L ||
            sizeBytes > MAX_CACHE_BYTES
        ) return false
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false
        return nowMs - lastModifiedMs <= TTL_MS
    }

    fun isStalePartial(lastModifiedMs: Long, nowMs: Long): Boolean =
        lastModifiedMs > 0L && nowMs >= lastModifiedMs &&
            nowMs - lastModifiedMs > PARTIAL_TTL_MS

    fun exceedsBudget(totalBytes: Long, fileCount: Int): Boolean =
        totalBytes > MAX_CACHE_BYTES || fileCount > MAX_CACHE_FILES
}
