package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSubtitleTranslationMergerTest {
    @Test
    fun preservesOriginalWordingAndTimeAxisWhenTranslationIsImported() {
        val original = listOf(
            SubLine(0, 1_000L, 2_000L, "Hello, everyone."),
            SubLine(1, 3_500L, 5_000L, "Welcome back."),
        )
        val translated = listOf(
            SubLine(0, 1_000L, 2_000L, "大家好。"),
            SubLine(1, 3_500L, 5_000L, "欢迎回来。"),
        )

        val result = ManualSubtitleTranslationMerger.merge(original, translated)

        assertEquals("Hello, everyone.", result[0].text)
        assertEquals("大家好。", result[0].zh)
        assertEquals(1_000L, result[0].startMs)
        assertEquals(2_000L, result[0].endMs)
        assertEquals("Welcome back.", result[1].text)
        assertEquals("欢迎回来。", result[1].zh)
    }

    @Test
    fun matchesByTimestampRatherThanFileOrderAndExtractsChineseFromBilingualCue() {
        val original = listOf(
            SubLine(0, 1_000L, 2_000L, "First"),
            SubLine(1, 3_000L, 4_000L, "Second"),
        )
        val translated = listOf(
            SubLine(0, 3_020L, 4_000L, "Second\n第二句"),
            SubLine(1, 1_000L, 2_020L, "First\n第一句"),
        )

        val result = ManualSubtitleTranslationMerger.merge(original, translated)

        assertEquals("第一句", result[0].zh)
        assertEquals("第二句", result[1].zh)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTranslationsThatDoNotMatchTheSourceTimeline() {
        ManualSubtitleTranslationMerger.merge(
            listOf(
                SubLine(0, 1_000L, 2_000L, "Hello"),
                SubLine(1, 3_000L, 4_000L, "World"),
            ),
            listOf(
                SubLine(0, 10_000L, 11_000L, "你好"),
                SubLine(1, 13_000L, 14_000L, "世界"),
            ),
        )
    }
}
