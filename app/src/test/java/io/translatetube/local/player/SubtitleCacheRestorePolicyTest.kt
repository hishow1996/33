package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCacheRestorePolicyTest {
    @Test
    fun importedTranslatedSubtitlesTakePriorityOverExistingTranslationCache() {
        assertFalse(
            SubtitleCacheRestorePolicy.shouldUseCachedRows(
                hasAuthoritativeImportedTranslation = true,
            ),
        )
    }

    @Test
    fun normalSubtitleFlowsKeepCacheFirstBehavior() {
        assertTrue(
            SubtitleCacheRestorePolicy.shouldUseCachedRows(
                hasAuthoritativeImportedTranslation = false,
            ),
        )
    }

    @Test
    fun localAndWebTranslationCachesAreInvalidatedWhenSourceTimelineIsMissing() {
        for (isYouTube in listOf(false)) {
            assertTrue(
                SubtitleCacheRestorePolicy.shouldInvalidateUnverifiedMediaTranslationCache(
                    hasImportedSource = false,
                    isYouTube = isYouTube,
                    hasMediaSource = true,
                    hasValidatedSourceTimeline = false,
                ),
            )
        }
    }

    @Test
    fun mediaTranslationCacheCanBeReusedWhenItsSourceTimelineIsValidated() {
        assertFalse(
            SubtitleCacheRestorePolicy.shouldInvalidateUnverifiedMediaTranslationCache(
                hasImportedSource = false,
                isYouTube = false,
                hasMediaSource = true,
                hasValidatedSourceTimeline = true,
            ),
        )
    }

    @Test
    fun authoritativeImportedSubtitlesAndYoutubeValidationPathAreNotInvalidatedByThisGuard() {
        assertFalse(
            SubtitleCacheRestorePolicy.shouldInvalidateUnverifiedMediaTranslationCache(
                hasImportedSource = true,
                isYouTube = false,
                hasMediaSource = true,
                hasValidatedSourceTimeline = false,
            ),
        )
        assertFalse(
            SubtitleCacheRestorePolicy.shouldInvalidateUnverifiedMediaTranslationCache(
                hasImportedSource = false,
                isYouTube = true,
                hasMediaSource = true,
                hasValidatedSourceTimeline = false,
            ),
        )
    }
}
