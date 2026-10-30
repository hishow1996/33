package io.translatetube.local.player

/**
 * Decide whether an unchanged source cue may be treated as an untranslated/original-audio
 * fallback for this engine. Local neural models must retry source echoes; term-like cues
 * (names, acronyms, URLs) are already allowed by SubtitleTranslationQualityPolicy.
 */
internal object TranslationBatchFallbackPolicy {
    fun shouldTreatSourceEchoAsUntranslatedFallback(engineId: String): Boolean =
        engineId in setOf(
            "local",
            "mlkit-local",
            "google-hybrid",
            "microsoft-web",
            "baidu-web",
        )

    /**
     * Keep a small number of invalid subtitle rows on original audio only when the batch has
     * usable translations. Global provider/model failures must still fail the task.
     */
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
