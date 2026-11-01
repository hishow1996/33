package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedSpeakerAnalysisEligibilityPolicyTest {
    private fun shouldAnalyze(
        provider: String = "local",
        cacheKey: String? = "source-local-asr",
        expectedKey: String = "source-local-asr",
        validated: Boolean = true,
        imported: Boolean = false,
        producedNow: Boolean = false,
    ) = CachedSpeakerAnalysisEligibilityPolicy.shouldAnalyzeCachedTranscript(
        asrProvider = provider,
        sourceCacheKey = cacheKey,
        expectedSourceCacheKey = expectedKey,
        hasValidatedSourceCache = validated,
        hasImportedSource = imported,
        transcriptProducedInThisRun = producedNow,
    )

    @Test
    fun acceptsOnlyTheCurrentValidatedLocalAsrSourceCache() {
        assertTrue(shouldAnalyze())
    }

    @Test
    fun rejectsCaptionOrOtherSourceCacheKeys() {
        assertFalse(shouldAnalyze(cacheKey = "youtube-caption-cache"))
        assertFalse(shouldAnalyze(cacheKey = null))
        assertFalse(shouldAnalyze(expectedKey = "different-source"))
    }

    @Test
    fun rejectsCloudAsrAndImportedSubtitles() {
        assertFalse(shouldAnalyze(provider = "elevenlabs"))
        assertFalse(shouldAnalyze(imported = true))
    }

    @Test
    fun doesNotRepeatSpeakerAnalysisForFreshAsrOutput() {
        assertFalse(shouldAnalyze(producedNow = true))
    }

    @Test
    fun requiresValidatedSourceCache() {
        assertFalse(shouldAnalyze(validated = false))
    }
}
