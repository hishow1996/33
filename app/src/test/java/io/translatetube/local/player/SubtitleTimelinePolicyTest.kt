package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTimelinePolicyTest {
    @Test
    fun cueStartIsInclusiveAndCueEndIsExclusive() {
        val lines = listOf(
            line(0, 1_000L, 2_000L, "第一句", "First"),
            line(1, 2_500L, 3_500L, "第二句", "Second"),
        )

        val timeline = SubtitleTimelinePolicy(lines)
        assertNull(timeline.lineAtPosition(-1L))
        assertNull(timeline.lineAtPosition(999L))
        assertEquals("第一句", timeline.lineAtPosition(1_000L)?.zh)
        assertEquals("第一句", timeline.lineAtPosition(1_999L)?.zh)
        assertNull(timeline.lineAtPosition(2_000L))
        assertNull(timeline.lineAtPosition(2_499L))
        assertEquals("第二句", timeline.lineAtPosition(2_500L)?.zh)
        assertNull(timeline.lineAtPosition(3_500L))
    }

    @Test
    fun laterStartedActiveCueWinsOverAnOverlappingEarlierCue() {
        val lines = listOf(
            line(0, 0L, 4_000L, "长句", "Long"),
            line(1, 1_000L, 2_000L, "短句", "Short"),
        )

        val timeline = SubtitleTimelinePolicy(lines)
        assertEquals("Short", timeline.lineAtPosition(1_500L)?.zh)
        assertEquals("Long", timeline.lineAtPosition(2_500L)?.zh)
        assertEquals("Long", timeline.lineAtPosition(3_999L)?.zh)
        assertNull(timeline.lineAtPosition(4_000L))
    }

    @Test
    fun emptyLinesAndEndBeforeStartCuesNeverDisplay() {
        assertNull(SubtitleTimelinePolicy(emptyList()).lineAtPosition(100L))
        val invalid = listOf(
            line(0, 2_000L, 1_000L, "无效", "Invalid"),
        )
        val timeline = SubtitleTimelinePolicy(invalid)
        assertNull(timeline.lineAtPosition(1_500L))
        assertNull(timeline.lineAtPosition(2_000L))
    }

    private fun line(index: Int, startMs: Long, endMs: Long, source: String, zh: String) =
        SubLine(index = index, startMs = startMs, endMs = endMs, text = source, zh = zh)
}
