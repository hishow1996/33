package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingLatenessStatsTest {
    private val sec = 1_000_000L

    @Test
    fun onTimeCuesAreNotCountedAsDelayed() {
        val stats = DubbingLatenessStats(reportEvery = 2)
        assertNull(stats.record(10 * sec, 9 * sec, 10 * sec, 1f))
        val summary = stats.record(20 * sec, 19 * sec, 20 * sec + 100_000L, 1f)!!
        assertEquals(2, summary.cues)
        assertEquals(0, summary.delayedCues)
        assertEquals(50L, summary.avgDelayMs)
        assertEquals(100L, summary.maxDelayMs)
        assertEquals(1f, summary.minCatchUpRatio, 0.0001f)
    }

    @Test
    fun separatesLatePreparationFromQueueingBehindPreviousCue() {
        val stats = DubbingLatenessStats(reportEvery = 100)
        // 句子挂上来时播放头已过期望时间 2 秒：准备太慢，开口就是播放头位置。
        stats.record(desiredUs = 10 * sec, playheadUs = 12 * sec, startedAtUs = 12 * sec, catchUpRatio = 1f)
        // 准备得及时，但被前一句顶后 1.5 秒。
        stats.record(desiredUs = 20 * sec, playheadUs = 19 * sec, startedAtUs = 21_500_000L, catchUpRatio = 1f)
        val summary = stats.drain()!!
        assertEquals(2, summary.delayedCues)
        assertEquals(1, summary.preparedLateCues)
        assertEquals(1, summary.queuedBehindCues)
        assertEquals(2_000L, summary.maxDelayMs)
        assertEquals(1_750L, summary.avgDelayMs)
    }

    @Test
    fun countsCompressionAndKeepsStrongestRatio() {
        val stats = DubbingLatenessStats(reportEvery = 100)
        stats.record(0L, 0L, 0L, 0.9f)
        stats.record(sec, sec, sec, 0.75f)
        stats.record(2 * sec, 2 * sec, 2 * sec, 1f)
        val summary = stats.drain()!!
        assertEquals(2, summary.compressedCues)
        assertEquals(0.75f, summary.minCatchUpRatio, 0.0001f)
    }

    @Test
    fun tracksLongestRunOfConsecutiveDelayedCuesAcrossBatches() {
        val stats = DubbingLatenessStats(reportEvery = 3)
        val late = { stats.record(0L, 0L, sec, 1f) }
        assertNull(late())
        assertNull(late())
        val first = stats.record(0L, 0L, 0L, 1f)!! // 2 迟到 + 1 准时
        assertEquals(2, first.longestDelayedStreak)

        assertNull(late())
        assertNull(late())
        val second = late()!!
        assertEquals(3, second.longestDelayedStreak)
        // 连续落后跨批时，下一批从当前连续长度起算。
        assertNull(late())
        assertNull(late())
        assertEquals(6, late()!!.longestDelayedStreak)
    }

    @Test
    fun rejectedCuesAndEmptyStatsProduceNothing() {
        val stats = DubbingLatenessStats(reportEvery = 1)
        assertNull(stats.record(0L, 0L, -1L, 1f))
        assertNull(stats.drain())
        assertTrue(stats.record(0L, 0L, 0L, 1f) != null)
        assertNull(stats.drain())
    }
}
