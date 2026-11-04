package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedTranslatedSubtitleConcurrencyPolicyTest {
    @Test
    fun bypassesMutexWhenOnlyTranslationIsUnwinding() {
        assertTrue(
            ImportedTranslatedSubtitleConcurrencyPolicy.canBypassPipelineMutex(
                previousPhase = Phase.TRANSLATING,
                hasActiveDubbingEngine = false,
                hasRetiringDubbingEngine = false,
            ),
        )
    }

    @Test
    fun keepsMutexWhenDubbingIsActiveOrRetiring() {
        assertFalse(
            ImportedTranslatedSubtitleConcurrencyPolicy.canBypassPipelineMutex(
                previousPhase = Phase.TRANSLATING,
                hasActiveDubbingEngine = true,
                hasRetiringDubbingEngine = false,
            ),
        )
        assertFalse(
            ImportedTranslatedSubtitleConcurrencyPolicy.canBypassPipelineMutex(
                previousPhase = Phase.TRANSLATING,
                hasActiveDubbingEngine = false,
                hasRetiringDubbingEngine = true,
            ),
        )
    }

    @Test
    fun keepsMutexOutsideTranslationPhase() {
        for (phase in listOf(Phase.PARSING, Phase.TRANSCRIBING, Phase.SYNTHESIZING, Phase.READY)) {
            assertFalse(
                "phase=$phase",
                ImportedTranslatedSubtitleConcurrencyPolicy.canBypassPipelineMutex(
                    previousPhase = phase,
                    hasActiveDubbingEngine = false,
                    hasRetiringDubbingEngine = false,
                ),
            )
        }
    }
}
