package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSyncHoldPolicyTest {
    @Test
    fun doesNotPauseWhenUtteranceFitsAtTheSafeTimelineRate() {
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(3_000L, 1_000L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(4_400L, 1_000L, 1.5f))
    }

    @Test
    fun pausesOnlyWhenSpeechCannotFitAtTheSafeTimelineRate() {
        assertTrue(DubbingSyncHoldPolicy.requiresVideoHold(3_001L, 1_000L, 1.0f))
        assertTrue(DubbingSyncHoldPolicy.requiresVideoHold(4_501L, 1_000L, 1.5f))
    }

    @Test
    fun invalidInputsDoNotRequestAnAutomaticPause() {
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(0L, 1_000L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(8_000L, 0L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.requiresVideoHold(8_000L, 1_000L, Float.NaN))
    }

    @Test
    fun doesNotMultiplySpeechByVideoSpeedWhileVideoWillBePaused() {
        assertEquals(3.0f, DubbingSyncHoldPolicy.playbackRate(3.0f, 2.0f, true), 0.001f)
        assertEquals(6.0f, DubbingSyncHoldPolicy.playbackRate(3.0f, 2.0f, false), 0.001f)
    }
}
