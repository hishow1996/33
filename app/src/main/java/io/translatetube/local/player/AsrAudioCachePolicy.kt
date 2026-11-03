package io.translatetube.local.player

/** Cache validity and bounded-storage rules for downloaded source audio used by ASR. */
internal object AsrAudioCachePolicy {
    const val TTL_MS = 7L * 24L * 60L * 60L * 1_000L
    const val PARTIAL_TTL_MS = 6L * 60L * 60L * 1_000L
    const val MAX_CACHE_BYTES = 768L * 1024L * 1024L
    const val MAX_CACHE_FILES = 12

    fun canReuse(
        isFile: Boolean,
        sizeBytes: Long,
        lastModifiedMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!isFile || sizeBytes <= 0L) return false
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false
        return nowMs - lastModifiedMs <= TTL_MS
    }

    /** Add cache payload sizes without allowing a signed Long overflow. */
    fun addCacheBytes(currentBytes: Long, appendBytes: Long): Long =
        if (currentBytes < 0L || appendBytes < 0L || appendBytes > Long.MAX_VALUE - currentBytes) {
            Long.MAX_VALUE
        } else {
            currentBytes + appendBytes
        }

    fun exceedsBudget(totalBytes: Long, fileCount: Int): Boolean =
        totalBytes > MAX_CACHE_BYTES || fileCount > MAX_CACHE_FILES

    /** Accept any non-empty audio file, while refusing byte-counter overflow. */
    fun canAppendBytes(currentBytes: Long, appendBytes: Long): Boolean =
        currentBytes >= 0L &&
            appendBytes >= 0L &&
            appendBytes <= Long.MAX_VALUE - currentBytes

    /** Only -1 means unknown to OkHttp; other negative lengths are invalid metadata. */
    fun isCompleteDownload(expectedBytes: Long, actualBytes: Long): Boolean =
        actualBytes > 0L &&
            (expectedBytes == -1L || expectedBytes == actualBytes)

    /** An abandoned sibling .part file is removable only after the stale-file grace period. */
    fun isStalePartial(lastModifiedMs: Long, nowMs: Long): Boolean =
        lastModifiedMs > 0L && nowMs >= lastModifiedMs &&
            nowMs - lastModifiedMs > PARTIAL_TTL_MS
}
