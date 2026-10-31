package io.translatetube.local.player

/** Validation and storage bounds for reusable local-video ASR PCM files. */
internal object LocalPcmAudioCachePolicy {
    // All source-audio cache types share the same freshness and abandoned-partial windows.
    const val TTL_MS = AsrAudioCachePolicy.TTL_MS
    const val PARTIAL_TTL_MS = AsrAudioCachePolicy.PARTIAL_TTL_MS
    const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    const val MAX_CACHE_FILES = 6

    /** Add PCM16-specific constraints on top of the shared source-audio cache validation. */
    fun canReuse(
        isFile: Boolean,
        sizeBytes: Long,
        lastModifiedMs: Long,
        nowMs: Long,
        isReadable: Boolean = true,
    ): Boolean {
        if (!isReadable || sizeBytes % 2L != 0L || sizeBytes > MAX_CACHE_BYTES) return false
        return AsrAudioCachePolicy.canReuse(
            isFile = isFile,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            nowMs = nowMs,
            isReadable = isReadable,
        )
    }

    fun isStalePartial(lastModifiedMs: Long, nowMs: Long): Boolean =
        AsrAudioCachePolicy.isStalePartial(lastModifiedMs, nowMs)

    fun exceedsBudget(totalBytes: Long, fileCount: Int): Boolean =
        totalBytes > MAX_CACHE_BYTES || fileCount > MAX_CACHE_FILES
}
