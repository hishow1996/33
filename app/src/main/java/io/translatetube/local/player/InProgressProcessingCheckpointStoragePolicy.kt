package io.translatetube.local.player

/**
 * Small deterministic rules shared by the durable checkpoint store and its regression tests.
 */
internal object InProgressProcessingCheckpointStoragePolicy {
    fun isUnchanged(
        cachedFingerprint: String,
        requestedFingerprint: String,
        fileExists: Boolean,
        cachedLength: Long,
        currentLength: Long,
        cachedLastModified: Long,
        currentLastModified: Long,
    ): Boolean =
        cachedFingerprint.isNotBlank() &&
            cachedFingerprint == requestedFingerprint &&
            fileExists &&
            cachedLength > 0L &&
            currentLength == cachedLength &&
            currentLastModified == cachedLastModified

    fun isExpired(
        savedAt: Long,
        nowMs: Long,
        maxAgeMs: Long,
        maxFutureSkewMs: Long,
    ): Boolean =
        savedAt <= 0L ||
            nowMs <= 0L ||
            maxAgeMs < 0L ||
            maxFutureSkewMs < 0L ||
            (savedAt > nowMs && savedAt - nowMs > maxFutureSkewMs) ||
            (savedAt <= nowMs && nowMs - savedAt > maxAgeMs)

    fun isExpiredFile(
        lastModifiedMs: Long,
        nowMs: Long,
        maxAgeMs: Long,
        maxFutureSkewMs: Long,
        fileBytes: Long,
        maxFileBytes: Long,
    ): Boolean =
        lastModifiedMs <= 0L ||
            nowMs <= 0L ||
            maxAgeMs < 0L ||
            maxFutureSkewMs < 0L ||
            fileBytes <= 0L ||
            maxFileBytes <= 0L ||
            fileBytes > maxFileBytes ||
            (lastModifiedMs > nowMs && lastModifiedMs - nowMs > maxFutureSkewMs) ||
            (lastModifiedMs <= nowMs && nowMs - lastModifiedMs > maxAgeMs)

    fun shouldReportSaveOutcome(
        status: InProgressCheckpointSaveStatus,
        reason: String?,
    ): Boolean = when (status) {
        InProgressCheckpointSaveStatus.FAILED -> true
        InProgressCheckpointSaveStatus.REJECTED -> !reason.isNullOrBlank()
        InProgressCheckpointSaveStatus.SAVED,
        InProgressCheckpointSaveStatus.UNCHANGED -> false
    }
}
