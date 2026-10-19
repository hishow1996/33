package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSyncHoldPolicyTest {
    @Test
    fun marksOnlySpeechThatStillExceedsTheMaximumTimelineRate() {
        assertFalse(DubbingSyncHoldPolicy.wouldOverrunCueWindow(1_800L, 1_000L, 1.0f))
        assertTrue(DubbingSyncHoldPolicy.wouldOverrunCueWindow(1_801L, 1_000L, 1.0f))
        assertTrue(DubbingSyncHoldPolicy.wouldOverrunCueWindow(8_000L, 1_000L, 0.5f))
        assertFalse(DubbingSyncHoldPolicy.wouldOverrunCueWindow(2_000L, 1_000L, 2.0f))
    }

    @Test
    fun shortUtterancesAreNeverSlowedToFillTheSubtitleWindow() {
        assertEquals(1.0f, DubbingSyncHoldPolicy.timelineRate(500L, 1_000L), 0.001f)
        assertEquals(1.2f, DubbingSyncHoldPolicy.timelineRate(1_200L, 1_000L), 0.001f)
        assertEquals(1.8f, DubbingSyncHoldPolicy.timelineRate(2_000L, 1_000L), 0.001f)
    }

    @Test
    fun invalidInputsDoNotReportAnOverrun() {
        assertFalse(DubbingSyncHoldPolicy.wouldOverrunCueWindow(0L, 1_000L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.wouldOverrunCueWindow(8_000L, 0L, 1.0f))
        assertFalse(DubbingSyncHoldPolicy.wouldOverrunCueWindow(8_000L, 1_000L, Float.NaN))
    }

    @Test
    fun playbackRateAlwaysTracksVideoSpeedEvenForAnOverlongCue() {
        assertEquals(3.0f, DubbingSyncHoldPolicy.playbackRate(3.0f, 2.0f), 0.001f)
        assertEquals(1.8f, DubbingSyncHoldPolicy.playbackRate(3.0f, 1.0f), 0.001f)
    }
}
