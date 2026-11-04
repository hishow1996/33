package io.translatetube.local.player

/**
 * Decide whether a very small number of unresolved cues can safely remain on original audio.
 * The threshold is measured against the complete subtitle timeline, not the final retry batch:
 * a one-row tail from a 770-row cached transcript is still only one failed cue.
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

    /** OpenAI-compatible and Gemini language models, which can stubbornly echo a short cue. */
    fun isApiLanguageModelEngine(engineId: String): Boolean =
        engineId == "openai" || engineId == "gemini"

    fun shouldKeepUnresolvedOnOriginalAudio(
        unresolvedCount: Int,
        batchSize: Int,
        hasUsableTranslation: Boolean,
        sourceEchoAsUntranslatedFallback: Boolean,
        isLocalTranslationEngine: Boolean,
        isApiLanguageModelEngine: Boolean = false,
        totalSubtitleRows: Int = batchSize,
        /** False after any malformed/misaligned provider response or transport-integrity failure. */
        responseIntegrityVerified: Boolean = true,
    ): Boolean {
        if (
            !responseIntegrityVerified ||
            unresolvedCount <= 0 ||
            batchSize <= 0 ||
            totalSubtitleRows <= 0 ||
            unresolvedCount >= totalSubtitleRows
        ) return false

        // At most 1% of the full timeline, capped at three cues. This permits an isolated
        // stubborn cue without masking a provider/model failure affecting a meaningful portion.
        val maximumRecoverableRows = ((totalSubtitleRows + 99) / 100).coerceIn(1, 3)
        return unresolvedCount <= maximumRecoverableRows &&
            hasUsableTranslation &&
            (sourceEchoAsUntranslatedFallback || isLocalTranslationEngine || isApiLanguageModelEngine)
    }
}
