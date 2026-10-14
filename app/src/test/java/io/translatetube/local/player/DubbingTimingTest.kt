package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun rejectsNegativeCueStartAndClampsExtremePlaybackPositions() {
        val invalid = DubbingTiming.createPlan(
            sampleCount = 1_000, sampleRate = 16_000,
            lineStartMs = -1L, lineEndMs = 1_000L,
            positionMs = 0L, playerSpeed = 1.0f,
        )
        val beforeStart = DubbingTiming.createPlan(
            sampleCount = 16_000, sampleRate = 16_000,
            lineStartMs = 500L, lineEndMs = 1_500L,
            positionMs = Long.MIN_VALUE, playerSpeed = 1.0f,
        )
        val afterEnd = DubbingTiming.createPlan(
            sampleCount = 16_000, sampleRate = 16_000,
            lineStartMs = 500L, lineEndMs = 1_500L,
            positionMs = Long.MAX_VALUE, playerSpeed = 1.0f,
        )

        assertEquals(0, invalid.sampleCount)
        assertEquals(0, beforeStart.startSample)
        assertEquals(0, afterEnd.sampleCount)
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
    fun fullUnmodifiedCueReusesInputArrayToAvoidAnotherLargePcmAllocation() {
        val samples = FloatArray(32_000) { 0.25f }
        val plan = DubbingTiming.createPlan(
            sampleCount = samples.size,
            sampleRate = 16_000,
            lineStartMs = 0,
            lineEndMs = 2_000,
            positionMs = 0,
            playerSpeed = 1.0f,
        )

        assertFalse(plan.truncated)
        assertSame(samples, DubbingTiming.slice(samples, plan, 16_000))
    }

    @Test
    fun overlongSpeechIsBoundedToCueWindowAndFaded() {
        val samples = FloatArray(5_000) { 1.0f }
        val plan = DubbingTiming.createPlan(
            sampleCount = samples.size, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 0, playerSpeed = 1.0f,
        )
        val playable = DubbingTiming.slice(samples, plan, 1_000)
        assertEquals(2_000, plan.sampleCount)
        assertEquals(2_000, playable.size)
        assertTrue(plan.truncated)
        assertEquals(0.0f, playable.last(), 0.0001f)
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

    @Test
    fun preservesAnEightyMillisecondWindowButSkipsAnythingShorter() {
        val samples = FloatArray(16_000) { 0.25f }
        val boundary = DubbingTiming.createPlan(
            sampleCount = samples.size, sampleRate = 16_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 920, playerSpeed = 1.0f,
        )
        val tooLate = DubbingTiming.createPlan(
            sampleCount = samples.size, sampleRate = 16_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 921, playerSpeed = 1.0f,
        )

        assertEquals(1_280, boundary.sampleCount)
        assertEquals(1_280, DubbingTiming.slice(samples, boundary, 16_000).size)
        assertEquals(0, tooLate.sampleCount)
        assertTrue(DubbingTiming.slice(samples, tooLate, 16_000).isEmpty())
    }

    @Test
    fun lateAudioTrackStartupSkipsElapsedSamplesWithoutExceedingBuffer() {
        assertEquals(
            1_600,
            DubbingTiming.lateStartSkipSamples(
                preparedAtMs = 400L,
                currentPositionMs = 450L,
                syncRate = 2.0f,
                sampleRate = 16_000,
                availableSampleCount = 32_000,
            ),
        )
        assertEquals(
            0,
            DubbingTiming.lateStartSkipSamples(
                preparedAtMs = 450L,
                currentPositionMs = 400L,
                syncRate = 2.0f,
                sampleRate = 16_000,
                availableSampleCount = 32_000,
            ),
        )
        assertEquals(
            32_000,
            DubbingTiming.lateStartSkipSamples(
                preparedAtMs = 400L,
                currentPositionMs = 2_000L,
                syncRate = 2.0f,
                sampleRate = 16_000,
                availableSampleCount = 32_000,
            ),
        )
    }

    @Test
    fun lateStartSkipHandlesExtremeTimestampsWithoutWrapping() {
        assertEquals(
            160,
            DubbingTiming.lateStartSkipSamples(
                preparedAtMs = Long.MIN_VALUE,
                currentPositionMs = 10L,
                syncRate = 1.0f,
                sampleRate = 16_000,
                availableSampleCount = 32_000,
            ),
        )
        assertEquals(
            32_000,
            DubbingTiming.lateStartSkipSamples(
                preparedAtMs = 0L,
                currentPositionMs = Long.MAX_VALUE,
                syncRate = 1.0f,
                sampleRate = 16_000,
                availableSampleCount = 32_000,
            ),
        )
    }

    @Test
    fun malformedPlanCannotReadPastAvailablePcm() {
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)
        val oversized = DubbingPlaybackPlan(
            startSample = 1,
            sampleCount = 100,
            syncRate = 1.0f,
            playbackRate = 1.0f,
            truncated = true,
        )

        assertEquals(2, DubbingTiming.slice(samples, oversized, 16_000).size)
    }

}
