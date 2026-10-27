package io.translatetube.local.player

/**
 * Keep a small number of invalid subtitle rows on original audio only when the batch has
 * usable translations. Global provider/model failures must still fail the task.
 */
internal object TranslationBatchFallbackPolicy {
    fun shouldKeepUnresolvedOnOriginalAudio(
        unresolvedCount: Int,
        batchSize: Int,
        hasUsableTranslation: Boolean,
        sourceEchoAsUntranslatedFallback: Boolean,
        isLocalTranslationEngine: Boolean,
    ): Boolean {
        if (unresolvedCount <= 0 || batchSize <= 0 || unresolvedCount >= batchSize) return false
        // Only treat a genuinely small tail of failures as recoverable. For a normal 10-row
        // batch this permits at most 2 rows; a fully/mostly failed model batch remains an error.
        val maximumRecoverableRows = (batchSize / 4).coerceAtLeast(1)
        return unresolvedCount <= maximumRecoverableRows &&
            hasUsableTranslation &&
            (sourceEchoAsUntranslatedFallback || isLocalTranslationEngine)
    }
}
