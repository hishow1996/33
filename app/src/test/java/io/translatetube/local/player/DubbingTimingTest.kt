package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingTimingTest {
    @Test
    fun playerSpeedIsAlwaysFiniteAndBounded() {
        assertEquals(1.0f, DubbingTiming.normalizePlayerSpeed(Float.NaN), 0.001f)
        assertEquals(1.0f, DubbingTiming.normalizePlayerSpeed(Float.POSITIVE_INFINITY), 0.001f)
        assertEquals(0.5f, DubbingTiming.normalizePlayerSpeed(0.1f), 0.001f)
        assertEquals(2.0f, DubbingTiming.normalizePlayerSpeed(3.0f), 0.001f)
    }

    @Test
    fun lateStartSkipsAudioUsingSubtitleTimeline() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 4_000, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 2_000,
            positionMs = 500, playerSpeed = 1.0f,
        )
        assertEquals(1_000, plan.startSample)
        assertEquals(3_000, plan.sampleCount)
        assertEquals(2.0f, plan.syncRate, 0.001f)
        assertFalse(plan.truncated)
    }

    @Test
    fun moderatelyOverlongSpeechUsesUpToThreeTimesCompressionBeforeTruncating() {
        val samples = FloatArray(2_500) { 0.5f }
        val plan = DubbingTiming.createPlan(
            sampleCount = samples.size, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 0, playerSpeed = 1.0f,
        )
        val playable = DubbingTiming.slice(samples, plan, 1_000)
        assertEquals(2_500, plan.sampleCount)
        assertEquals(2_500, playable.size)
        assertEquals(2.5f, plan.syncRate, 0.001f)
        assertEquals(2.5f, plan.playbackRate, 0.001f)
        assertFalse(plan.truncated)
    }

    @Test
    fun veryOverlongSpeechIsBoundedToThreeTimesAtNormalSpeedAndFaded() {
        val samples = FloatArray(5_000) { 1.0f }
        val plan = DubbingTiming.createPlan(
            sampleCount = samples.size, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 0, playerSpeed = 1.0f,
        )
        val playable = DubbingTiming.slice(samples, plan, 1_000)
        assertEquals(3_000, plan.sampleCount)
        assertEquals(3_000, playable.size)
        assertEquals(3.0f, plan.syncRate, 0.001f)
        assertTrue(plan.truncated)
        assertEquals(0.0f, playable.last(), 0.0001f)
    }

    @Test
    fun combinedOutputRateNeverExceedsFourTimesAtDoubleVideoSpeed() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 2_500, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 0, playerSpeed = 2.0f,
        )
        assertEquals(2.0f, plan.syncRate, 0.001f)
        assertEquals(4.0f, plan.playbackRate, 0.001f)
        assertTrue(plan.truncated)
    }

    @Test
    fun videoSpeedIsAppliedWithoutChangingSeekOffsetMath() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 4_000, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 2_000,
            positionMs = 500, playerSpeed = 1.5f,
        )
        assertEquals(1_000, plan.startSample)
        assertEquals(3.0f, plan.playbackRate, 0.001f)
    }

    @Test
    fun endedCueDoesNotRestartSpeech() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 2_000, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 1_000, playerSpeed = 1.0f,
        )
        assertEquals(0, plan.sampleCount)
        assertTrue(DubbingTiming.slice(FloatArray(2_000), plan, 1_000).isEmpty())
    }

    @Test
    fun skipsUnintelligibleFragmentWhenOnlyATinyWindowRemains() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 16_000, sampleRate = 16_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 950, playerSpeed = 1.0f,
        )
        assertEquals(16_000, plan.startSample)
        assertEquals(0, plan.sampleCount)
    }

    @Test
    fun shortSpeechNaturallyFinishesBeforeCueEnd() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 400, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 500, playerSpeed = 1.0f,
        )
        assertEquals(0, plan.sampleCount)
        assertEquals(1.0f, plan.syncRate, 0.001f)
    }
}
