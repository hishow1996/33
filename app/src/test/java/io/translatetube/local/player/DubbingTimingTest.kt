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
        assertEquals(0, beforeStart.sampleCount)
        assertEquals(0, afterEnd.sampleCount)
        assertEquals(16_000, afterEnd.startSample)
    }

    @Test
    fun lateStartSkipsAudioUsingSubtitleTimeline() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 4_000, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 2_000,
            positionMs = 500, playerSpeed = 1.0f,
        )
        assertEquals(900, plan.startSample)
        assertEquals(3_100, plan.sampleCount)
        assertEquals(1.8f, plan.syncRate, 0.001f)
        assertTrue(plan.overrunsWindow)
        assertFalse(plan.truncated)
    }

    @Test
    fun cancellationInterruptsLargeTimingWindowCopy() {
        val samples = FloatArray(20_000) { 0.25f }
        val plan = DubbingPlaybackPlan(1, samples.size - 1, 1.0f, 1.0f, false)
        var checks = 0
        var cancelled = false
        try {
            DubbingTiming.slice(samples, plan, 16_000) {
                checks++
                if (checks >= 3) throw IllegalStateException("cancel test")
            }
        } catch (_: IllegalStateException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue("slice checks cancellation between copy blocks", checks >= 3)
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
    fun speechBeyondSafeSyncRateIsPreservedAndMarkedAsTimelineOverrun() {
        val samples = FloatArray(5_000) { 1.0f }
        val plan = DubbingTiming.createPlan(
            sampleCount = samples.size, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 1_000,
            positionMs = 0, playerSpeed = 1.0f,
        )
        val playable = DubbingTiming.slice(samples, plan, 1_000)
        assertEquals(samples.size, plan.sampleCount)
        assertEquals(samples.size, playable.size)
        assertFalse(plan.truncated)
        assertTrue(plan.overrunsWindow)
        assertEquals(1.0f, playable.last(), 0.0001f)
    }

    @Test
    fun slightlyOverlongCueFitsWithinTheRaisedSynchronizationRate() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 1_400,
            sampleRate = 1_000,
            lineStartMs = 0L,
            lineEndMs = 1_000L,
            positionMs = 100L,
            playerSpeed = 1.0f,
        )
        assertEquals(140, plan.startSample)
        assertEquals(1_260, plan.sampleCount)
        assertFalse(plan.overrunsWindow)
        assertFalse(plan.truncated)
    }

    @Test
    fun lateStartingOverlongSpeechKeepsItsRemainingTail() {
        val samples = FloatArray(5_000) { 0.25f }
        val plan = DubbingTiming.createPlan(
            sampleCount = samples.size,
            sampleRate = 1_000,
            lineStartMs = 0L,
            lineEndMs = 1_000L,
            positionMs = 200L,
            playerSpeed = 1.0f,
        )
        val playable = DubbingTiming.slice(samples, plan, 1_000)
        assertEquals(360, plan.startSample)
        assertEquals(4_640, plan.sampleCount)
        assertTrue(plan.overrunsWindow)
        assertFalse(plan.truncated)
        assertEquals(4_640, playable.size)
    }

    @Test
    fun longUtteranceBeyondNaturalRateIsPreservedWithoutTruncation() {
        val samples = FloatArray(3_000) { 0.2f }
        val plan = DubbingTiming.createPlan(
            sampleCount = samples.size,
            sampleRate = 1_000,
            lineStartMs = 0L,
            lineEndMs = 1_000L,
            positionMs = 0L,
            playerSpeed = 1.0f,
        )

        assertEquals(1.8f, plan.syncRate, 0.001f)
        assertEquals(samples.size, plan.sampleCount)
        assertTrue(plan.overrunsWindow)
        assertFalse(plan.truncated)
        assertSame(samples, DubbingTiming.slice(samples, plan, 1_000))
    }

    @Test
    fun videoSpeedIsAppliedWithoutChangingSeekOffsetMath() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 4_000, sampleRate = 1_000,
            lineStartMs = 0, lineEndMs = 2_000,
            positionMs = 500, playerSpeed = 1.5f,
        )
        assertEquals(900, plan.startSample)
        assertEquals(1.8f, plan.syncRate, 0.001f)
        assertEquals(2.7f, plan.playbackRate, 0.001f)
        assertTrue(plan.overrunsWindow)
    }

    @Test
    fun lateCueCanPlayItsCompletePcmWhenScheduledForCatchUp() {
        val samples = FloatArray(3_200) { 0.2f }
        val plan = DubbingTiming.createCompleteCuePlan(
            sampleCount = samples.size,
            sampleRate = 1_000,
            cueDurationMs = 50L,
            playerSpeed = 1.0f,
        )
        assertEquals(0, plan.startSample)
        assertEquals(samples.size, plan.sampleCount)
        assertFalse(plan.truncated)
        assertTrue(plan.overrunsWindow)
        assertEquals(samples.size, DubbingTiming.slice(samples, plan, 1_000).size)
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

    @Test
    fun callerCannotRaiseSyncRateBeyondTheRateUsedByPlaybackOutput() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 10_000,
            sampleRate = 1_000,
            lineStartMs = 0,
            lineEndMs = 1_000,
            positionMs = 0,
            playerSpeed = 2.0f,
            maxSyncRate = 10.0f,
        )

        assertEquals(1.8f, plan.syncRate, 0.001f)
        assertEquals(3.0f, plan.playbackRate, 0.001f)
        assertEquals(10_000, plan.sampleCount)
        assertTrue(plan.overrunsWindow)
        assertFalse(plan.truncated)
    }


    @Test
    fun midCuePlaybackFadesInBrieflyWithoutMutatingCachedPcm() {
        val cached = FloatArray(4_000) { 0.5f }
        val plan = DubbingPlaybackPlan(
            startSample = 100,
            sampleCount = 1_000,
            syncRate = 1.0f,
            playbackRate = 1.0f,
            truncated = false,
        )

        val playable = DubbingTiming.slice(cached, plan, sampleRate = 16_000)

        assertEquals(1_000, playable.size)
        assertEquals(0.0f, playable[0], 0.0f)
        assertTrue(playable[64] in 0.0f..0.5f)
        assertEquals(0.5f, playable[127], 0.0001f)
        assertEquals(0.5f, playable[128], 0.0001f)
        assertEquals(0.5f, cached[100], 0.0f)
    }


@Test
    fun cueBeforeStartNeverProducesPlayableSamples() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 16_000,
            sampleRate = 16_000,
            lineStartMs = 2_000L,
            lineEndMs = 3_000L,
            positionMs = 1_999L,
            playerSpeed = 1f,
        )
        assertEquals(0, plan.startSample)
        assertEquals(0, plan.sampleCount)
    }

    @Test
    fun positionExactlyAtCueEndIsTerminal() {
        val plan = DubbingTiming.createPlan(
            sampleCount = 16_000,
            sampleRate = 16_000,
            lineStartMs = 2_000L,
            lineEndMs = 3_000L,
            positionMs = 3_000L,
            playerSpeed = 1f,
        )
        assertEquals(16_000, plan.startSample)
        assertEquals(0, plan.sampleCount)
    }

    @Test
    fun playableEndBorrowsTheSilentGapButNeverTouchesTheNextCue() {
        // 空隙很大：最多借 3 秒
        assertEquals(6_000L, DubbingTiming.playableEndMs(1_000L, 3_000L, 10_000L))
        // 空隙不大：借完后给下一条字幕留 120ms
        assertEquals(3_380L, DubbingTiming.playableEndMs(1_000L, 3_000L, 3_500L))
        // 空隙不够 / 紧挨着 / 重叠：原样返回
        assertEquals(3_000L, DubbingTiming.playableEndMs(1_000L, 3_000L, 3_100L))
        assertEquals(3_000L, DubbingTiming.playableEndMs(1_000L, 3_000L, 3_000L))
        assertEquals(3_000L, DubbingTiming.playableEndMs(1_000L, 3_000L, 2_000L))
        // 最后一句没有下一条字幕
        assertEquals(6_000L, DubbingTiming.playableEndMs(1_000L, 3_000L, Long.MAX_VALUE))
        // 非法时间窗和接近 Long 上限的值不被改动
        assertEquals(500L, DubbingTiming.playableEndMs(1_000L, 500L, 10_000L))
        assertEquals(Long.MAX_VALUE - 1L, DubbingTiming.playableEndMs(0L, Long.MAX_VALUE - 1L, Long.MAX_VALUE))
    }

    @Test
    fun extendCueWindowsOnlyWidensSpokenCuesAndKeepsOrderAndIndices() {
        val lines = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 1_000L, "a", "甲"),
            io.translatetube.local.subs.SubLine(1, 1_500L, 2_500L, "b", "乙"),
            // 没有译文的字幕不配音，但它的开始时间仍然限制前一句能借多少
            io.translatetube.local.subs.SubLine(2, 2_550L, 3_000L, "c", null),
            io.translatetube.local.subs.SubLine(3, 3_000L, 4_000L, "d", "丁"),
        )

        val extended = DubbingTiming.extendCueWindows(lines)

        assertEquals(lines.map { it.index }, extended.map { it.index })
        assertEquals(lines.map { it.startMs }, extended.map { it.startMs })
        assertEquals(
            // a: 下一条 1500 → 借到 1380；b: 下一条 2550 放不下缓冲 → 不变；c 无译文 → 不变；d 最后一句 → +3000
            listOf(1_380L, 2_500L, 3_000L, 7_000L),
            extended.map { it.endMs },
        )
        assertEquals(lines.map { it.zh }, extended.map { it.zh })
    }

    @Test
    fun borrowedGapAvoidsSpeedUpAndTruncationForALongTranslation() {
        // A 2.4 s cue exceeds the natural 1.35x limit; borrowing a silent gap can avoid a video hold.
        val sampleRate = 16_000
        val sampleCount = 2_400 * sampleRate / 1_000
        val original = DubbingTiming.createPlan(
            sampleCount = sampleCount, sampleRate = sampleRate,
            lineStartMs = 1_000L, lineEndMs = 2_000L, positionMs = 1_000L, playerSpeed = 1.0f,
        )
        val borrowedEnd = DubbingTiming.playableEndMs(1_000L, 2_000L, 10_000L)
        val borrowed = DubbingTiming.createPlan(
            sampleCount = sampleCount, sampleRate = sampleRate,
            lineStartMs = 1_000L, lineEndMs = borrowedEnd, positionMs = 1_000L, playerSpeed = 1.0f,
        )

        assertFalse(original.truncated)
        assertEquals(1.35f, original.syncRate, 0.001f)
        assertTrue(original.overrunsWindow)
        assertEquals(sampleCount, original.sampleCount)
        assertFalse(borrowed.truncated)
        assertEquals(1.0f, borrowed.syncRate, 0.001f)
        assertEquals(sampleCount, borrowed.sampleCount)
    }

}
