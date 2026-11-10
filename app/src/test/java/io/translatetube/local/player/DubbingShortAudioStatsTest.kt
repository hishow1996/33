package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingShortAudioStatsTest {
    @Test
    fun countsOnlyClearlyShortCuesAndRanksByGap() {
        val stats = DubbingShortAudioStats(reportEvery = 4)
        assertNull(stats.record(1, 0L, 5_000L, 4_800L, "正常"))
        assertNull(stats.record(2, 10_000L, 8_000L, 3_000L, "短一些"))
        assertNull(stats.record(3, 20_000L, 10_000L, 2_000L, "短很多"))
        val summary = stats.record(4, 30_000L, 1_000L, 200L, "窗口小，差距不够")

        assertNotNull(summary)
        assertEquals(4, summary!!.cues)
        assertEquals(2, summary.shortCues)
        assertEquals(listOf(3, 2), summary.worst.map { it.row })
        assertEquals(8_000L, summary.worst[0].gapMs)
    }

    @Test
    fun ignoresInvalidAndDrainsPartialBatch() {
        val stats = DubbingShortAudioStats(reportEvery = 10)
        assertNull(stats.record(1, 0L, 0L, 500L, "无窗口"))
        assertNull(stats.drain())
        stats.record(2, 0L, 6_000L, 1_000L, "短")
        val summary = stats.drain()
        assertNotNull(summary)
        assertEquals(1, summary!!.cues)
        assertEquals(16, summary.avgAudioToWindowPercent)
        assertTrue(DubbingShortAudioStats.isShort(6_000L, 1_000L))
        assertNull(stats.drain())
    }
}
