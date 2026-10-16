package io.translatetube.local.translate

/**
 * Bounds the cost of serializing the complete subtitle cache after each translated batch while
 * keeping early progress durable and always flushing the final successful batch.
 */
internal object TranslationCheckpointPolicy {
    fun shouldPersistCheckpoint(
        completedBatchCount: Int,
        totalBatches: Int,
        interval: Int = 3,
    ): Boolean {
        if (completedBatchCount <= 0 || totalBatches <= 0 || interval <= 0) return false
        if (completedBatchCount > totalBatches) return false
        return completedBatchCount == 1 ||
            completedBatchCount % interval == 0 ||
            completedBatchCount == totalBatches
    }
}
