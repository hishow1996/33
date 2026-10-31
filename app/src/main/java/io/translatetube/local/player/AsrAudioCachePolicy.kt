package io.translatetube.local.player

/** Cache validity rules for remote ASR audio; size is not capped by this policy. */
internal object AsrAudioCachePolicy {
    const val TTL_MS = 7L * 24L * 60L * 60L * 1_000L
    const val PARTIAL_TTL_MS = 6L * 60L * 60L * 1_000L

    fun canReuse(
        isFile: Boolean,
        sizeBytes: Long,
        lastModifiedMs: Long,
        nowMs: Long,
        isReadable: Boolean = true,
    ): Boolean {
        if (!isFile || !isReadable || sizeBytes <= 0L) return false
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false
        return nowMs - lastModifiedMs <= TTL_MS
    }

    /** A cached media container must expose an audio track usable by this device. */
    fun hasUsableAudioTrack(
        audioTrackCount: Int,
        selectedAudioDecoderAvailable: Boolean?,
    ): Boolean =
        audioTrackCount > 0 && selectedAudioDecoderAvailable != false

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
