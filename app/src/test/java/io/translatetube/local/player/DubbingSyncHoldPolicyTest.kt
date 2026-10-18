package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSyncHoldPolicyTest {
    @Test
    fun doesNotPauseWhenUtteranceFitsAtTheSafeTimelineRate() {
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(1_350L, 1_000L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(2_025L, 1_000L, 1.5f))
    }

    @Test
    fun pausesOnlyWhenSpeechCannotFitAtTheSafeTimelineRate() {
        assertTrue(DubbingSyncHoldPolicy.requiresVideoHold(1_351L, 1_000L, 1.0f))
        assertTrue(DubbingSyncHoldPolicy.requiresVideoHold(2_026L, 1_000L, 1.5f))
    }

    @Test
    fun shortUtterancesAreNeverSlowedToFillTheSubtitleWindow() {
        assertEquals(1.0f, DubbingSyncHoldPolicy.timelineRate(500L, 1_000L), 0.001f)
        assertEquals(1.2f, DubbingSyncHoldPolicy.timelineRate(1_200L, 1_000L), 0.001f)
        assertEquals(1.35f, DubbingSyncHoldPolicy.timelineRate(2_000L, 1_000L), 0.001f)
    }

    @Test
    fun invalidInputsDoNotRequestAnAutomaticPause() {
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(0L, 1_000L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(8_000L, 0L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(8_000L, 1_000L, Float.NaN))
    }

    @Test
    fun doesNotMultiplySpeechByVideoSpeedWhileVideoWillBePaused() {
        assertEquals(1.35f, DubbingSyncHoldPolicy.playbackRate(3.0f, 2.0f, true), 0.001f)
        assertEquals(2.7f, DubbingSyncHoldPolicy.playbackRate(3.0f, 2.0f, false), 0.001f)
    }
}
