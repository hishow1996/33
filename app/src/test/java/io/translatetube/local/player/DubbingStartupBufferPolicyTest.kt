package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingStartupBufferPolicyTest {
    private fun lines(count: Int, spacingMs: Long = 2_000L): List<SubLine> =
        (0 until count).map { index ->
            val start = index * spacingMs
            SubLine(
                index = index,
                startMs = start,
                endMs = start + 1_500L,
                text = "source $index",
                zh = "中文 $index",
            )
        }

    @Test
    fun immediateModeOnlyWaitsForFirstUsableSpeechCue() {
        val input = listOf(
            SubLine(0, -1L, 0L, "bad cue", "invalid"),
            SubLine(1, 0L, 1_000L, "blank translation", ""),
            SubLine(2, 1_000L, 2_000L, "source", "有效字幕"),
        )
        val plan = DubbingStartupBufferPolicy.plan(input, immediateMode = true)
        assertEquals(listOf(2), plan.lineIndices)
        assertEquals(2_500L, plan.waitTimeoutMs)
    }

    @Test
    fun generatedModePreparesSevenToEightCuesAcrossUpcomingTimeline() {
        val plan = DubbingStartupBufferPolicy.plan(lines(12), immediateMode = false)
        assertTrue(plan.lineIndices.size >= 7)
        assertTrue(plan.lineIndices.size <= 8)
        assertEquals(7, plan.minimumCueCount)
        assertEquals(16_000L, plan.lookAheadMs)
        assertEquals(9_000L, plan.waitTimeoutMs)
    }

    @Test
    fun slowSynthesisUsesDeeperStartupBuffer() {
        val baseline = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisTimelineLagFactor = 1.0f,
        )
        val slow = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisTimelineLagFactor = 1.8f,
        )
        assertEquals(16_000L, baseline.lookAheadMs)
        assertEquals(24_000L, slow.lookAheadMs)
        assertEquals(7, baseline.minimumCueCount)
        assertEquals(7, slow.minimumCueCount)
        assertEquals(8, slow.lineIndices.size)
        assertFalse(slow.waitForAllBeforePlayback)
        assertTrue(slow.lineIndices.size >= baseline.lineIndices.size)
    }

    @Test
    fun sustainedSevereSlowdownStillStartsAfterBoundedBufferWait() {
        val plan = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisTimelineLagFactor = 2.2f,
            synthesisSampleCount = 2,
        )
        assertFalse(plan.waitForAllBeforePlayback)
        assertEquals(24_000L, plan.lookAheadMs)
        assertEquals(7, plan.minimumCueCount)
        assertEquals(8, plan.lineIndices.size)
        assertEquals(12_000L, plan.waitTimeoutMs)
        assertTrue(plan.lineIndices.size < 16)
    }

    @Test
    fun oneSlowSynthesisSampleOnlyIncreasesBufferInsteadOfWaitingForEverything() {
        val plan = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisTimelineLagFactor = 2.2f,
            synthesisSampleCount = 1,
        )
        assertFalse(plan.waitForAllBeforePlayback)
        assertEquals(24_000L, plan.lookAheadMs)
        assertEquals(7, plan.minimumCueCount)
        assertEquals(8, plan.lineIndices.size)
    }

    @Test
    fun immediateModeNeverEscalatesToFullGeneratedBuffer() {
        val plan = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = true,
            synthesisTimelineLagFactor = 5.0f,
            synthesisSampleCount = 100,
        )
        assertFalse(plan.waitForAllBeforePlayback)
        assertEquals(1, plan.minimumCueCount)
        assertEquals(2_500L, plan.waitTimeoutMs)
    }

    @Test
    fun fastSynthesisCanStartWithSmallerBuffer() {
        val plan = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisTimelineLagFactor = 0.5f,
        )
        assertEquals(14_000L, plan.lookAheadMs)
        assertEquals(7, plan.minimumCueCount)
        assertEquals(8, plan.lineIndices.size)
        assertEquals(8_000L, plan.waitTimeoutMs)
    }

    @Test
    fun fewerThanSevenAvailableSpeechCuesDoNotWaitForNonexistentLines() {
        val plan = DubbingStartupBufferPolicy.plan(lines(4), immediateMode = false)
        assertEquals(4, plan.minimumCueCount)
        assertEquals(listOf(0, 1, 2, 3), plan.lineIndices)
    }

    @Test
    fun emptyOrNonPlayableInputProducesEmptyPlan() {
        val empty = DubbingStartupBufferPolicy.plan(emptyList(), immediateMode = false)
        assertTrue(empty.lineIndices.isEmpty())
        val invalid = DubbingStartupBufferPolicy.plan(
            listOf(SubLine(0, 0L, 0L, "bad", "坏字幕")),
            immediateMode = false,
        )
        assertTrue(invalid.lineIndices.isEmpty())
    }
}
