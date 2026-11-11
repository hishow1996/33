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
    fun startupBufferCountsFromTheCuePlayingAtTheResumePoint() {
        val input = lines(20) // cue n spans [n*2000, n*2000+1500)
        val spoken = DubbingStartupBufferPolicy.spokenCueIndices(input)

        assertEquals(spoken, DubbingStartupBufferPolicy.spokenCuesFrom(input, spoken, 0L))
        // 10_500 ms is inside cue 5 ([10000, 11500)): count from it, not from the video start.
        assertEquals(
            (5 until 20).toList(),
            DubbingStartupBufferPolicy.spokenCuesFrom(input, spoken, 10_500L),
        )
        // In the gap after cue 5 the next cue to be reached is 6.
        assertEquals(
            (6 until 20).toList(),
            DubbingStartupBufferPolicy.spokenCuesFrom(input, spoken, 11_600L),
        )
        assertEquals(emptyList<Int>(), DubbingStartupBufferPolicy.spokenCuesFrom(input, spoken, 1_000_000L))

        val plan = DubbingStartupBufferPolicy.plan(
            input,
            DubbingStartupBufferPolicy.spokenCuesFrom(input, spoken, 10_500L),
            immediateMode = false,
        )
        assertEquals(5, plan.lineIndices.first())
    }

    @Test
    fun playbackStartMatchesTheResumeRule() {
        // Not resumed: restarting, or watched 10 s or less.
        assertEquals(0L, DubbingStartupBufferPolicy.playbackStartMs(true, 600L, 3_000L))
        assertEquals(0L, DubbingStartupBufferPolicy.playbackStartMs(false, 10L, 3_000L))
        // Resumed mid-video.
        assertEquals(600_000L, DubbingStartupBufferPolicy.playbackStartMs(false, 600L, 3_000L))
        // Within the last 30 s of a known duration: play from the start.
        assertEquals(0L, DubbingStartupBufferPolicy.playbackStartMs(false, 2_980L, 3_000L))
        // Unknown duration still resumes.
        assertEquals(120_000L, DubbingStartupBufferPolicy.playbackStartMs(false, 120L, 0L))
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
    fun bracketedSoundCaptionsDoNotCountTowardTheSpokenStartupBuffer() {
        val input = listOf(
            SubLine(0, 0L, 900L, "[music]", "[音乐]"),
            SubLine(1, 1_000L, 2_000L, "Hello.", "你好。"),
            SubLine(2, 2_100L, 3_000L, "[applause]", "[掌声]"),
            SubLine(3, 3_100L, 4_000L, "Goodbye.", "再见。"),
            SubLine(4, 4_100L, 5_000L, "♪♪", "♪♪"),
        )
        assertEquals(listOf(1, 3), DubbingStartupBufferPolicy.spokenCueIndices(input))
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
