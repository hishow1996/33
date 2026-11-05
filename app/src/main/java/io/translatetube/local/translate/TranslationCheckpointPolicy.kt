package io.translatetube.local.translate

/**
 * Controls the less-frequent Room snapshot of the full subtitle timeline.
 * The first completed batch and final batch are always saved; intermediate Room snapshots
 * are saved every [DEFAULT_INTERVAL] batches. The separate in-progress recovery checkpoint
 * remains responsible for per-batch process-death recovery.
 */
internal object TranslationCheckpointPolicy {
    const val DEFAULT_INTERVAL = 5

    fun shouldPersistCheckpoint(
        completedBatchCount: Int,
        totalBatches: Int,
        interval: Int = DEFAULT_INTERVAL,
    ): Boolean {
        if (completedBatchCount <= 0 || totalBatches <= 0 || interval <= 0) return false
        if (completedBatchCount > totalBatches) return false
        return completedBatchCount == 1 ||
            completedBatchCount % interval == 0 ||
            completedBatchCount == totalBatches
    }
}
