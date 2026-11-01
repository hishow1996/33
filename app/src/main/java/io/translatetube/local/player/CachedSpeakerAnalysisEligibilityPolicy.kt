package io.translatetube.local.player

/**
 * Restricts delayed speaker backfill to a validated local-ASR source transcript cache.
 * Caption tracks, imported subtitles, cloud ASR and newly produced transcripts are excluded.
 */
internal object CachedSpeakerAnalysisEligibilityPolicy {
    fun shouldAnalyzeCachedTranscript(
        asrProvider: String,
        sourceCacheKey: String?,
        expectedSourceCacheKey: String,
        hasValidatedSourceCache: Boolean,
        hasImportedSource: Boolean,
        transcriptProducedInThisRun: Boolean,
    ): Boolean =
        asrProvider.trim().equals("local", ignoreCase = true) &&
            !sourceCacheKey.isNullOrBlank() &&
            sourceCacheKey == expectedSourceCacheKey &&
            hasValidatedSourceCache &&
            !hasImportedSource &&
            !transcriptProducedInThisRun
}
