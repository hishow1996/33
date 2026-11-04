package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadySessionCompletionPolicyTest {
    private fun canRegister(
        phase: Phase = Phase.READY,
        lineCount: Int = 12,
        dubbingOn: Boolean = true,
        dubbingHasErrors: Boolean = false,
        waitingForTranscriptionEngine: Boolean = false,
        waitingForDubbingEngine: Boolean = false,
        translationsComplete: Boolean = true,
    ) = ReadySessionCompletionPolicy.canRegisterSuccessfulResult(
        phase = phase,
        lineCount = lineCount,
        dubbingOn = dubbingOn,
        dubbingHasErrors = dubbingHasErrors,
        waitingForTranscriptionEngine = waitingForTranscriptionEngine,
        waitingForDubbingEngine = waitingForDubbingEngine,
        translationsComplete = translationsComplete,
    )

    @Test
    fun completeChineseSourceRowsAreAcceptedButMissingRowsAreNot() {
        assertTrue(ReadySessionCompletionPolicy.hasCompleteTranslations(hasUntranslatedLines = false))
        assertFalse(ReadySessionCompletionPolicy.hasCompleteTranslations(hasUntranslatedLines = true))
    }

    @Test
    fun missingTranslationsBlockSuccessfulSnapshotRegistration() {
        assertFalse(canRegister(translationsComplete = false))
    }

    @Test
    fun emptyTimelineAndDubbingFailuresCannotBeRegisteredAsSuccessful() {
        assertFalse(canRegister(lineCount = 0))
        assertFalse(canRegister(dubbingHasErrors = true))
        assertFalse(canRegister(dubbingOn = false))
    }

    @Test
    fun waitingEnginesAndNonReadyPhasesCannotBeRegisteredAsSuccessful() {
        assertFalse(canRegister(waitingForTranscriptionEngine = true))
        assertFalse(canRegister(waitingForDubbingEngine = true))
        assertFalse(canRegister(phase = Phase.SYNTHESIZING))
    }

    @Test
    fun completeReadyDubbingResultCanBeRegistered() {
        assertTrue(canRegister())
    }
}
