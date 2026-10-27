package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchFallbackPolicyTest {
    @Test
    fun keepsOnlyFailedRowsOnOriginalAudioWhenLocalBatchHasUsableTranslations() {
        assertTrue(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 1,
                hasUsableTranslation = true,
                sourceEchoAsUntranslatedFallback = false,
                isLocalTranslationEngine = true,
            )
        )
    }

    @Test
    fun neverMasksAnEntirelyFailedLocalModelBatch() {
        assertFalse(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 10,
                hasUsableTranslation = false,
                sourceEchoAsUntranslatedFallback = false,
                isLocalTranslationEngine = true,
            )
        )
    }

    @Test
    fun keepsRemoteApiFailuresStrictUnlessItsExistingFallbackPolicyAllowsIt() {
        assertFalse(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 1,
                hasUsableTranslation = true,
                sourceEchoAsUntranslatedFallback = false,
                isLocalTranslationEngine = false,
            )
        )
        assertTrue(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 1,
                hasUsableTranslation = true,
                sourceEchoAsUntranslatedFallback = true,
                isLocalTranslationEngine = false,
            )
        )
    }
}
