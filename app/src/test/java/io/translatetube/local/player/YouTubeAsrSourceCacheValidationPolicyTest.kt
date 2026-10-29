package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAsrSourceCacheValidationPolicyTest {
    @Test
    fun keepsValidTimelineCacheWhenAudioLanguageHintIsUnavailable() {
        assertFalse(
            YouTubeAsrSourceCacheValidationPolicy.shouldInvalidate(
                expectedSourceLanguage = null,
                detectedTextLanguage = "ko",
                savedSourceLanguage = "ko",
                isCaptionCache = false,
            ),
        )
    }

    @Test
    fun stillInvalidatesAsrCacheWhenKnownAudioLanguageConflicts() {
        assertTrue(
            YouTubeAsrSourceCacheValidationPolicy.shouldInvalidate(
                expectedSourceLanguage = "ko",
                detectedTextLanguage = "en",
                savedSourceLanguage = "en",
                isCaptionCache = false,
            ),
        )
    }

    @Test
    fun keepsConsistentFallbackCaptionWhenOriginalAudioLanguageIsUnknown() {
        assertFalse(
            YouTubeAsrSourceCacheValidationPolicy.shouldInvalidate(
                expectedSourceLanguage = null,
                detectedTextLanguage = "en",
                savedSourceLanguage = "en",
                isCaptionCache = true,
            ),
        )
    }

    @Test
    fun stillRejectsConflictingLanguageLabelsOnFallbackCaption() {
        assertTrue(
            YouTubeAsrSourceCacheValidationPolicy.shouldInvalidate(
                expectedSourceLanguage = "ko",
                detectedTextLanguage = "ko",
                savedSourceLanguage = "en",
                isCaptionCache = true,
            ),
        )
    }
    @Test
    fun unknownAudioHintDoesNotDropTranscriptForConflictingSavedLabel() {
        assertFalse(
            YouTubeAsrSourceCacheValidationPolicy.shouldInvalidate(
                expectedSourceLanguage = null,
                detectedTextLanguage = "ko",
                savedSourceLanguage = "en",
                isCaptionCache = false,
            ),
        )
    }

    @Test
    fun detectedAsrTextLanguageOverridesStaleSavedLabelWhenAudioHintIsUnknown() {
        assertEquals(
            "ko",
            YouTubeAsrSourceCacheValidationPolicy.resolvedCachedLanguage(
                expectedSourceLanguage = null,
                detectedTextLanguage = "ko",
                savedSourceLanguage = "en",
                isCaptionCache = false,
            ),
        )
    }

    @Test
    fun verifiedAudioLanguageWinsWhenTextDetectionIsUnavailable() {
        assertEquals(
            "ko",
            YouTubeAsrSourceCacheValidationPolicy.resolvedCachedLanguage(
                expectedSourceLanguage = "ko",
                detectedTextLanguage = "auto",
                savedSourceLanguage = "ko",
                isCaptionCache = false,
            ),
        )
    }

}
