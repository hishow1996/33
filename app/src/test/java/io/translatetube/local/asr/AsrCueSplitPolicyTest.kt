package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrCueSplitPolicyTest {
    private fun assertTimeline(start: Long, end: Long, rows: List<Triple<Long, Long, String>>) {
        assertEquals(start, rows.first().first)
        assertEquals(end, rows.last().second)
        for (i in 0 until rows.size - 1) assertEquals(rows[i].second, rows[i + 1].first)
        for (row in rows) assertTrue(row.second > row.first)
    }

    @Test
    fun shortLinesAreReturnedUnchanged() {
        val original = listOf(Triple(0L, 3_000L, "Short line here."))
        assertEquals(original, AsrCueSplitPolicy.split(0L, 3_000L, "Short line here."))
        // 时间长但只有一句：拆不出东西，也保持原样
        assertEquals(
            listOf(Triple(0L, 9_000L, "Short line here.")),
            AsrCueSplitPolicy.split(0L, 9_000L, "Short line here."),
        )
    }

    @Test
    fun aLongEnglishSegmentIsSplitIntoSentencesAcrossItsOwnTimeRange() {
        val text = "This is the first sentence of the talk about trees. " +
            "Here is a second sentence that is quite long indeed too. " +
            "And finally a third sentence to close it out well."
        val rows = AsrCueSplitPolicy.split(0L, 25_000L, text)
        assertEquals(
            listOf(
                "This is the first sentence of the talk about trees.",
                "Here is a second sentence that is quite long indeed too.",
                "And finally a third sentence to close it out well.",
            ),
            rows.map { it.third },
        )
        assertTimeline(0L, 25_000L, rows)
        // 字多的句子分到的时间更长
        assertTrue(rows[1].second - rows[1].first > rows[0].second - rows[0].first)
    }

    @Test
    fun aLongChineseSegmentIsSplitAtFullWidthPunctuation() {
        val text = "今天天气很好我们一起去公园玩吧。你觉得怎么样呢？我觉得非常不错而且很有趣！那我们现在就出发好不好呀。"
        val rows = AsrCueSplitPolicy.split(1_000L, 21_000L, text)
        assertEquals(
            listOf(
                "今天天气很好我们一起去公园玩吧。你觉得怎么样呢？",
                "我觉得非常不错而且很有趣！那我们现在就出发好不好呀。",
            ),
            rows.map { it.third },
        )
        assertTimeline(1_000L, 21_000L, rows)
    }

    @Test
    fun aSentenceWithoutPunctuationIsCutAtSpacesAndLosesNoWords() {
        val text = "word ".repeat(60).trim()
        val rows = AsrCueSplitPolicy.split(0L, 20_000L, text)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.third.length <= AsrCueSplitPolicy.MAX_OTHER_CHARS })
        assertEquals(text, rows.joinToString(" ") { it.third })
        assertTimeline(0L, 20_000L, rows)
    }

    @Test
    fun anAbbreviationDoesNotEndASentence() {
        val text = "Dr. Smith said the plan would cost three million dollars this year, " +
            "which is much more than before. Then he left."
        val rows = AsrCueSplitPolicy.split(0L, 12_000L, text)
        assertEquals(
            listOf(
                "Dr. Smith said the plan would cost three million dollars this year,",
                "which is much more than before. Then he left.",
            ),
            rows.map { it.third },
        )
        assertFalse(rows.any { it.third == "Dr." })
    }

    @Test
    fun decimalNumbersAndInvalidRangesAreNotSplit() {
        val line = "It costs 3.5 dollars."
        assertEquals(listOf(Triple(0L, 2_000L, line)), AsrCueSplitPolicy.split(0L, 2_000L, line))
        assertEquals(listOf(Triple(5L, 5L, "x")), AsrCueSplitPolicy.split(5L, 5L, "x"))
        assertEquals(listOf(Triple(0L, 9_000L, "  ")), AsrCueSplitPolicy.split(0L, 9_000L, "  "))
    }

    @Test
    fun finishingASegmentDropsCreditLinesAndSplitsTheRest() {
        assertEquals(
            emptyList<Triple<Long, Long, String>>(),
            AsrCueSplitPolicy.finishSegmentRows(
                listOf(Triple(0L, 5_000L, "Subtitles by the Amara.org community")),
            ),
        )
        val loop = "I'm going to go home. ".repeat(10).trim()
        assertEquals(
            listOf(Triple(0L, 20_000L, "I'm going to go home.")),
            AsrCueSplitPolicy.finishSegmentRows(listOf(Triple(0L, 20_000L, loop))),
        )
    }
}
