package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePreviewSegmentPolicyTest {
    @Test
    fun keepsShortCaptionAsOneFragment() {
        val caption = "这是一句简短字幕。"
        assertEquals(listOf(caption), SubtitlePreviewSegmentPolicy.split(caption))
    }

    @Test
    fun splitsLongChineseCaptionWithoutDroppingCharacters() {
        val caption = "这是第一段较长的中文字幕，需要完整显示出来。".repeat(4)
        val segments = SubtitlePreviewSegmentPolicy.split(caption, maxVisibleUnits = 18f)

        assertTrue(segments.size > 1)
        assertEquals(caption, segments.joinToString(separator = ""))
        assertTrue(segments.all { it.codePointCount(0, it.length) <= 18 })
    }

    @Test
    fun prefersSentenceBoundariesWhenSplitting() {
        val caption = "前面这句话需要先显示完整。后面这句话也需要完整显示。"
        val segments = SubtitlePreviewSegmentPolicy.split(caption, maxVisibleUnits = 14f)

        assertTrue(segments.size > 1)
        assertTrue(segments.first().endsWith("。"))
        assertEquals(caption, segments.joinToString(separator = ""))
    }

    @Test
    fun preservesLatinWordsSpacesAndSupplementaryCharacters() {
        val caption = "MacBook Pro 可以修复🙂，但需要先检查主板和接口。"
        val segments = SubtitlePreviewSegmentPolicy.split(caption, maxVisibleUnits = 10f)

        assertTrue(segments.size > 1)
        assertEquals(caption, segments.joinToString(separator = ""))
        assertTrue(segments.none { it.contains("…") })
    }

    @Test
    fun mergedCaptionUsesTheWholeTwoCueWindowFromEitherLine() {
        val lines = listOf(
            SubLine(0, 1_000L, 2_000L, "first", "第一句"),
            SubLine(1, 2_200L, 3_400L, "second", "第二句"),
        )
        val merged = "第一句，第二句"

        val fromFirst = SubtitlePreviewSegmentPolicy.resolveTiming(lines, 0, merged)
        val fromSecond = SubtitlePreviewSegmentPolicy.resolveTiming(lines, 1, merged)

        assertEquals(1_000L, fromFirst.cueKeyMs)
        assertEquals(2_400L, fromFirst.durationMs)
        assertEquals(fromFirst, fromSecond)
    }

    @Test
    fun mergedCaptionDoesNotAddCommaAfterExistingSentencePunctuation() {
        val lines = listOf(
            SubLine(0, 500L, 1_400L, "first", "第一句。"),
            SubLine(1, 1_500L, 2_500L, "second", "第二句"),
        )

        val timing = SubtitlePreviewSegmentPolicy.resolveTiming(lines, 1, "第一句。第二句")

        assertEquals(500L, timing.cueKeyMs)
        assertEquals(2_000L, timing.durationMs)
    }

    @Test
    fun ordinaryCaptionUsesItsOwnCueWindow() {
        val lines = listOf(SubLine(0, 4_000L, 5_600L, "source", "译文"))

        val timing = SubtitlePreviewSegmentPolicy.resolveTiming(lines, 0, null)

        assertEquals(4_000L, timing.cueKeyMs)
        assertEquals(1_600L, timing.durationMs)
    }
}
