package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
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
    fun generatedModePreparesSeveralCuesAcrossUpcomingTimeline() {
        val plan = DubbingStartupBufferPolicy.plan(lines(12), immediateMode = false)
        assertTrue(plan.lineIndices.size >= 3)
        assertTrue(plan.lineIndices.size <= 6)
        assertEquals(8_000L, plan.lookAheadMs)
        assertEquals(15_000L, plan.waitTimeoutMs)
    }

    @Test
    fun slowSynthesisUsesDeeperStartupBuffer() {
        val baseline = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisRealtimeFactor = 1.0f,
        )
        val slow = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisRealtimeFactor = 1.8f,
        )
        assertEquals(8_000L, baseline.lookAheadMs)
        assertEquals(12_000L, slow.lookAheadMs)
        assertTrue(slow.lineIndices.size >= baseline.lineIndices.size)
    }

    @Test
    fun fastSynthesisCanStartWithSmallerBuffer() {
        val plan = DubbingStartupBufferPolicy.plan(
            lines(16),
            immediateMode = false,
            synthesisRealtimeFactor = 0.5f,
        )
        assertEquals(5_000L, plan.lookAheadMs)
        assertEquals(2, plan.minimumCueCount)
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
