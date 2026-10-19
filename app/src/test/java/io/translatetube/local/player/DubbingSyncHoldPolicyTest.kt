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
    fun timelineRateUsesHigherCapOnlyWhenCallerRequestsRecovery() {
        assertEquals(
            1.8f,
            DubbingSyncHoldPolicy.timelineRate(3_000L, 1_000L),
            0.001f,
        )
        assertEquals(
            2.0f,
            DubbingSyncHoldPolicy.timelineRate(
                2_000L, 1_000L, DubbingTiming.MAX_CATCH_UP_SYNC_RATE,
            ),
            0.001f,
        )
        assertEquals(
            DubbingTiming.MAX_CATCH_UP_SYNC_RATE,
            DubbingSyncHoldPolicy.timelineRate(
                3_000L, 1_000L, maxSyncRate = 9.0f,
            ),
            0.001f,
        )
    }

    @Test
    fun timelineRateHonorsRequestedCatchUpRateEvenWhenSentenceFitsNaturally() {
        assertEquals(
            1.0f,
            DubbingSyncHoldPolicy.timelineRate(500L, 1_000L),
            0.001f,
        )
        assertEquals(
            1.55f,
            DubbingSyncHoldPolicy.timelineRate(
                estimatedSpeechMs = 500L,
                cueDurationMs = 1_000L,
                maxSyncRate = DubbingTiming.MAX_CATCH_UP_SYNC_RATE,
                targetRate = 1.55f,
            ),
            0.001f,
        )
        assertEquals(
            DubbingTiming.MAX_CATCH_UP_SYNC_RATE,
            DubbingSyncHoldPolicy.timelineRate(
                estimatedSpeechMs = 500L,
                cueDurationMs = 1_000L,
                maxSyncRate = DubbingTiming.MAX_CATCH_UP_SYNC_RATE,
                targetRate = 9.0f,
            ),
            0.001f,
        )
    }

    @Test
    fun spokenCharacterCountExcludesSpacesAndPunctuation() {
        assertEquals(5, DubbingSyncHoldPolicy.spokenCodePointCount("你好， 世界！"))
        assertEquals(4, DubbingSyncHoldPolicy.spokenCodePointCount("Hello,"))
        assertEquals(0, DubbingSyncHoldPolicy.spokenCodePointCount("……？！"))
    }

    @Test
    fun punctuationPauseEstimateGroupsConsecutiveMarksAndIgnoresDecimalPoints() {
        assertEquals(75L, DubbingSyncHoldPolicy.punctuationPauseMs("等一下，继续"))
        assertEquals(165L, DubbingSyncHoldPolicy.punctuationPauseMs("真的吗？！"))
        assertEquals(210L, DubbingSyncHoldPolicy.punctuationPauseMs("我想想……"))
        assertEquals(0L, DubbingSyncHoldPolicy.punctuationPauseMs("版本 3.14"))
    }

    @Test
    fun speechDurationEstimateAddsNaturalPauseBudget() {
        assertEquals(
            515L,
            DubbingSyncHoldPolicy.estimateSpeechDurationMs("你好，世界！", 100.0),
        )
        assertEquals(
            400L,
            DubbingSyncHoldPolicy.estimateSpeechDurationMs("Hello world", 100.0),
        )
    }

    @Test
    fun directTtsDurationCalibrationSubtractsPunctuationPauseBeforeLearningPace() {
        val updated = DubbingSyncHoldPolicy.updateSpeechDurationEstimate(
            currentMsPerCodePoint = 220.0,
            elapsedMs = 1_600L,
            codePointCount = 10,
            appliedSpeechRate = 2.0f,
            estimatedPunctuationPauseMs = 200L,
        )
        assertEquals(244.0, updated, 0.001)
    }

    @Test
    fun directTtsDurationEstimateLearnsFromCompletedSpeech() {
        val updated = DubbingSyncHoldPolicy.updateSpeechDurationEstimate(
            currentMsPerCodePoint = 220.0,
            elapsedMs = 1_000L,
            codePointCount = 10,
            appliedSpeechRate = 2.0f,
        )
        assertEquals(214.0, updated, 0.001)
    }

    @Test
    fun directTtsDurationCalibrationIgnoresInvalidOrTinySamples() {
        assertEquals(
            220.0,
            DubbingSyncHoldPolicy.updateSpeechDurationEstimate(220.0, 50L, 10, 1.0f),
            0.001,
        )
        assertEquals(
            220.0,
            DubbingSyncHoldPolicy.updateSpeechDurationEstimate(220.0, 1_000L, 0, 1.0f),
            0.001,
        )
        assertEquals(
            220.0,
            DubbingSyncHoldPolicy.updateSpeechDurationEstimate(220.0, 1_000L, 10, Float.NaN),
            0.001,
        )
    }

    @Test
    fun directTtsDurationCalibrationClampsExtremeObservations() {
        val faster = DubbingSyncHoldPolicy.updateSpeechDurationEstimate(220.0, 120L, 10, 1.0f)
        val slower = DubbingSyncHoldPolicy.updateSpeechDurationEstimate(220.0, 60_000L, 1, 1.0f)
        assertEquals(172.0, faster, 0.001)
        assertEquals(424.0, slower, 0.001)
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
