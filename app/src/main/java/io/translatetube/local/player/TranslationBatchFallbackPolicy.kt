package io.translatetube.local.player

/**
 * Keep a small number of invalid subtitle rows on original audio only when the batch has
 * usable translations. Global provider/model failures must still fail the task.
 */
internal object TranslationBatchFallbackPolicy {
    fun shouldKeepUnresolvedOnOriginalAudio(
        unresolvedCount: Int,
        hasUsableTranslation: Boolean,
        sourceEchoAsUntranslatedFallback: Boolean,
        isLocalTranslationEngine: Boolean,
    ): Boolean =
        unresolvedCount > 0 &&
            hasUsableTranslation &&
            (sourceEchoAsUntranslatedFallback || isLocalTranslationEngine)
}
