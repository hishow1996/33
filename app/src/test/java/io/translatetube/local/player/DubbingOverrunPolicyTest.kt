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
                pcmOutputActive = false,
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
                pcmOutputActive = true,
                currentAudioFinished = false,
            )
        )
        assertFalse(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 4,
                nextLineIndex = 5,
                liveUtteranceActive = false,
                pcmOutputActive = true,
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
                pcmOutputActive = true,
                currentAudioFinished = false,
            )
        )
        assertFalse(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 2,
                nextLineIndex = 2,
                liveUtteranceActive = true,
                pcmOutputActive = true,
                currentAudioFinished = false,
            )
        )
    }

    @Test
    fun activeNonOverrunPcmCueStillFinishesBeforeNextCueStarts() {
        assertTrue(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 5,
                nextLineIndex = 6,
                liveUtteranceActive = false,
                pcmOutputActive = true,
                currentAudioFinished = false,
            )
        )
        assertFalse(
            DubbingOverrunPolicy.shouldHold(
                currentLineIndex = 5,
                nextLineIndex = 6,
                liveUtteranceActive = false,
                pcmOutputActive = false,
                currentAudioFinished = true,
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
                pcmOutputActive = false,
                currentAudioFinished = false,
            )
        )
    }
}
