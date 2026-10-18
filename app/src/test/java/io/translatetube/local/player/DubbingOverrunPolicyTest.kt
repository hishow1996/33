package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingOverrunPolicyTest {
    @Test
    fun liveSystemTtsUtteranceSurvivesSubtitleBoundaryUntilCallbackCompletes() {
        assertTrue(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 2,
                nextLineIndex = 3,
                liveUtteranceActive = true,
                pcmOverrunActive = false,
                currentAudioFinished = false,
            )
        )
    }

    @Test
    fun pcmOverrunRemainsActiveUntilItsFinalBufferFinishes() {
        assertTrue(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 4,
                nextLineIndex = 5,
                liveUtteranceActive = false,
                pcmOverrunActive = true,
                currentAudioFinished = false,
            )
        )
        assertFalse(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 4,
                nextLineIndex = 5,
                liveUtteranceActive = false,
                pcmOverrunActive = true,
                currentAudioFinished = true,
            )
        )
    }

    @Test
    fun doesNotHoldWhenThereIsNoCurrentCueOrTheCueHasNotChanged() {
        assertFalse(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = -1,
                nextLineIndex = 2,
                liveUtteranceActive = true,
                pcmOverrunActive = true,
                currentAudioFinished = false,
            )
        )
        assertFalse(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 2,
                nextLineIndex = 2,
                liveUtteranceActive = true,
                pcmOverrunActive = true,
                currentAudioFinished = false,
            )
        )
    }

    @Test
    fun completedNonOverrunCueDoesNotBlockNextCue() {
        assertFalse(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 1,
                nextLineIndex = 2,
                liveUtteranceActive = false,
                pcmOverrunActive = false,
                currentAudioFinished = false,
            )
        )
    }
}
