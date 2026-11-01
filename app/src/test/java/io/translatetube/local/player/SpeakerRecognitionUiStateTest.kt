package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRecognitionUiStateTest {
    @Test
    fun activePhasesAreBusy() {
        val active = listOf(
            SpeakerRecognitionPhase.PREPARING,
            SpeakerRecognitionPhase.DOWNLOADING_MODELS,
            SpeakerRecognitionPhase.ANALYZING,
            SpeakerRecognitionPhase.APPLYING,
            SpeakerRecognitionPhase.CANCELLING,
        )
        active.forEach { phase ->
            assertTrue(SpeakerRecognitionUiState(phase = phase).isActive)
        }
    }

    @Test
    fun terminalPhasesAreNotBusy() {
        val terminal = listOf(
            SpeakerRecognitionPhase.IDLE,
            SpeakerRecognitionPhase.SUCCEEDED,
            SpeakerRecognitionPhase.FAILED,
            SpeakerRecognitionPhase.CANCELLED,
        )
        terminal.forEach { phase ->
            assertFalse(SpeakerRecognitionUiState(phase = phase).isActive)
        }
    }
}
