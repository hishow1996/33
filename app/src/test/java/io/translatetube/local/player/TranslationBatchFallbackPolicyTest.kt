package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchFallbackPolicyTest {
    @Test
    fun onlyEnginesWithAnExplicitOriginalAudioFallbackAcceptSourceEchoes() {
        assertTrue(TranslationBatchFallbackPolicy.shouldTreatSourceEchoAsUntranslatedFallback("local"))
        assertTrue(TranslationBatchFallbackPolicy.shouldTreatSourceEchoAsUntranslatedFallback("google-hybrid"))
        assertTrue(TranslationBatchFallbackPolicy.shouldTreatSourceEchoAsUntranslatedFallback("microsoft-web"))
        assertFalse(TranslationBatchFallbackPolicy.shouldTreatSourceEchoAsUntranslatedFallback("small100"))
        assertFalse(TranslationBatchFallbackPolicy.shouldTreatSourceEchoAsUntranslatedFallback("opus-mt"))
        assertFalse(
            TranslationBatchFallbackPolicy.shouldTreatSourceEchoAsUntranslatedFallback(
                "single-pair-direct-small100"
            )
        )
    }

    @Test
    fun keepsOnlyFailedRowsOnOriginalAudioWhenLocalBatchHasUsableTranslations() {
        assertTrue(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 1,
                batchSize = 10,
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
                batchSize = 10,
                hasUsableTranslation = false,
                sourceEchoAsUntranslatedFallback = false,
                isLocalTranslationEngine = true,
            )
        )
    }

    @Test
    fun rejectsFallbackWhenTheWholeCurrentBatchFailedEvenIfEarlierBatchesSucceeded() {
        assertFalse(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 10,
                batchSize = 10,
                hasUsableTranslation = false,
                sourceEchoAsUntranslatedFallback = false,
                isLocalTranslationEngine = true,
            )
        )
    }

    @Test
    fun rejectsFallbackWhenTooManyRowsInTheCurrentBatchAreMissing() {
        assertFalse(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 4,
                batchSize = 10,
                hasUsableTranslation = true,
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
                batchSize = 10,
                hasUsableTranslation = true,
                sourceEchoAsUntranslatedFallback = false,
                isLocalTranslationEngine = false,
            )
        )
        assertTrue(
            TranslationBatchFallbackPolicy.shouldKeepUnresolvedOnOriginalAudio(
                unresolvedCount = 1,
                batchSize = 10,
                hasUsableTranslation = true,
                sourceEchoAsUntranslatedFallback = true,
                isLocalTranslationEngine = false,
            )
        )
    }
}
