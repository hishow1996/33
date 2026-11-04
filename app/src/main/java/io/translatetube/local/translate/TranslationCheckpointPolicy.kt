package io.translatetube.local.translate

/**
 * Persists each successful translation batch by default so an interrupted background task
 * resumes from the latest completed batch. Callers may opt into a wider interval when the
 * serialization cost is more important than minimizing repeat work.
 */
internal object TranslationCheckpointPolicy {
    fun shouldPersistCheckpoint(
        completedBatchCount: Int,
        totalBatches: Int,
        interval: Int = 1,
    ): Boolean {
        if (completedBatchCount <= 0 || totalBatches <= 0 || interval <= 0) return false
        if (completedBatchCount > totalBatches) return false
        return completedBatchCount == 1 ||
            completedBatchCount % interval == 0 ||
            completedBatchCount == totalBatches
    }
}
