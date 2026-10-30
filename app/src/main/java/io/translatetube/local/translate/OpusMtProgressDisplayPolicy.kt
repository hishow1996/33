package io.translatetube.local.translate

/** Stable UI values for OPUS-MT progress, independent of transient state-flow initialization. */
internal data class OpusMtProgressDisplay(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Int,
    val visible: Boolean,
)

internal object OpusMtProgressDisplayPolicy {
    fun resolve(
        state: OpusMtDownloadState,
        configuredTotalBytes: Long,
    ): OpusMtProgressDisplay {
        val totalBytes = state.totalBytes.takeIf { it > 0L }
            ?: configuredTotalBytes.takeIf { it > 0L }
            ?: -1L
        val complete = state.status == OpusMtStatus.READY ||
            state.status == OpusMtStatus.VERIFYING ||
            state.hasCompleteFiles
        val downloadedBytes = when {
            complete && totalBytes > 0L -> totalBytes
            totalBytes > 0L -> state.downloadedBytes.coerceIn(0L, totalBytes)
            else -> state.downloadedBytes.coerceAtLeast(0L)
        }
        // An incomplete transfer must never look finished due to stale or malformed progress.
        val progress = if (complete) 100 else state.progress.coerceIn(0, 99)
        val visible = state.status == OpusMtStatus.DOWNLOADING ||
            state.status == OpusMtStatus.VERIFYING ||
            state.status == OpusMtStatus.READY ||
            complete ||
            downloadedBytes > 0L

        return OpusMtProgressDisplay(
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            progress = progress,
            visible = visible,
        )
    }
}
