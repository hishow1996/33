package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSentenceMergePolicyTest {
    // YouTube 自动字幕的"滚动"格式：每个 cue 把上一行再显示一遍，只有新出现的行带逐词时间标签。
    private val rollingJapaneseVtt = listOf(
        "WEBVTT",
        "",
        "00:00:00.000 --> 00:00:02.000 align:start position:0%",
        " ",
        "今日<00:00:00.500><c>は</c><00:00:00.800><c>晴れ</c><00:00:01.200><c>です</c><00:00:01.500><c>。</c><00:00:01.600><c>散歩</c>",
        "",
        "00:00:02.000 --> 00:00:02.010 align:start position:0%",
        "今日は晴れです。散歩",
        " ",
        "",
        "00:00:02.010 --> 00:00:04.000 align:start position:0%",
        "今日は晴れです。散歩",
        "に<00:00:02.500><c>行き</c><00:00:03.000><c>ます</c><00:00:03.400><c>。</c>",
        "",
    ).joinToString("\n")

    private val rollingJapaneseRows = listOf(
        SubLine(0, 0L, 2000L, "今日は晴れです。散歩"),
        SubLine(1, 2010L, 4000L, "に行きます。"),
    )

    @Test
    fun mergesFragmentedRowsIntoSentencesUsingWordTimes() {
        val result = CaptionSentenceMergePolicy.merge(
            rows = rollingJapaneseRows,
            rawVtt = rollingJapaneseVtt,
            minTimedTags = 3,
            minRows = 2,
        )

        val lines = result.lines
        assertNotNull(result.reason, lines)
        assertEquals(2, lines!!.size)
        assertEquals("今日は晴れです。", lines[0].text)
        assertEquals(0L, lines[0].startMs)
        // 句末标点 "。" 的时间会拖到下一个字出现，这里最多保留 1.5 秒停顿；本例它只有 100ms。
        assertEquals(1600L, lines[0].endMs)
        assertEquals("散歩に行きます。", lines[1].text)
        assertEquals(1600L, lines[1].startMs)
        assertEquals(4000L, lines[1].endMs)
        assertEquals(listOf(0, 1), lines.map { it.index })
    }

    @Test
    fun keepsOriginalRowsWhenThereIsNoSentencePunctuation() {
        val result = CaptionSentenceMergePolicy.merge(
            rows = rollingJapaneseRows,
            rawVtt = rollingJapaneseVtt.replace("。", ""),
            minTimedTags = 3,
            minRows = 2,
        )

        assertNull(result.lines)
        assertTrue(result.reason, result.reason.contains("标点"))
    }

    @Test
    fun keepsOriginalRowsWhenMergedTextDoesNotMatchTheRows() {
        val unrelatedRows = listOf(
            SubLine(0, 0L, 2000L, "全然違う文章です"),
            SubLine(1, 2010L, 4000L, "別の内容になります"),
        )

        val result = CaptionSentenceMergePolicy.merge(
            rows = unrelatedRows,
            rawVtt = rollingJapaneseVtt,
            minTimedTags = 3,
            minRows = 2,
        )

        assertNull(result.lines)
        assertTrue(result.reason, result.reason.contains("对不上"))
    }

    @Test
    fun keepsOriginalRowsWhenThereAreNoWordTimingTags() {
        val plainVtt = listOf(
            "WEBVTT",
            "",
            "00:00:00.000 --> 00:00:02.000",
            "今日は晴れです。",
            "",
        ).joinToString("\n")

        val result = CaptionSentenceMergePolicy.merge(
            rows = listOf(SubLine(0, 0L, 2000L, "今日は晴れです。")),
            rawVtt = plainVtt,
            minRows = 1,
        )

        assertNull(result.lines)
    }

    @Test
    fun splitsOverlongSentenceAtCommaNearTheMiddle() {
        val token = "語語語"
        val line = StringBuilder(token)
        for (i in 1..29) {
            val suffix = when (i) {
                9, 19 -> "、"
                29 -> "。"
                else -> ""
            }
            line.append("<00:00:").append(i.toString().padStart(2, '0')).append(".000><c>")
                .append(token).append(suffix).append("</c>")
        }
        val vtt = listOf(
            "WEBVTT",
            "",
            "00:00:00.000 --> 00:00:40.000",
            line.toString(),
            "",
        ).joinToString("\n")
        val rows = listOf(
            SubLine(0, 0L, 10_000L, "語".repeat(30)),
            SubLine(1, 10_000L, 20_000L, "語".repeat(30)),
            SubLine(2, 20_000L, 40_000L, "語".repeat(30)),
        )

        val result = CaptionSentenceMergePolicy.merge(rows, vtt, minTimedTags = 5, minRows = 1)

        val lines = result.lines
        assertNotNull(result.reason, lines)
        // 90 个字超过一句的上限，在最靠近中间的"、"处（第 10 个词后）切开。
        assertEquals(2, lines!!.size)
        assertTrue(lines[0].text.endsWith("、"))
        assertTrue(lines[1].text.endsWith("。"))
        assertEquals(30, lines[0].text.count { it == '語' })
        assertEquals(60, lines[1].text.count { it == '語' })
        assertEquals(0L, lines[0].startMs)
        assertEquals(10_000L, lines[0].endMs)
        assertEquals(10_000L, lines[1].startMs)
    }

    @Test
    fun addsSpaceBetweenLinesForSpaceSeparatedLanguages() {
        val vtt = listOf(
            "WEBVTT",
            "",
            "00:00:00.000 --> 00:00:02.000",
            " ",
            "hello<00:00:00.500><c> world</c>",
            "",
            "00:00:02.000 --> 00:00:04.000",
            "hello world",
            "again<00:00:02.500><c> now.</c>",
            "",
        ).joinToString("\n")
        val rows = listOf(
            SubLine(0, 0L, 2000L, "hello world"),
            SubLine(1, 2000L, 4000L, "again now."),
        )

        val result = CaptionSentenceMergePolicy.merge(rows, vtt, minTimedTags = 2, minRows = 2)

        val lines = result.lines
        assertNotNull(result.reason, lines)
        assertEquals(1, lines!!.size)
        assertEquals("hello world again now.", lines[0].text)
        assertEquals(0L, lines[0].startMs)
        assertEquals(4000L, lines[0].endMs)
    }

    @Test
    fun splitsUntimedLinesAtSentencePunctuationInProportionToLength() {
        // 没有逐词标签的整行文字（YouTube 偶尔整块给出）：按句末标点切开，按字数分配这一行的时间。
        val vtt = listOf(
            "WEBVTT",
            "",
            "00:00:00.000 --> 00:00:10.000",
            "あいうえお。かきくけこ。",
            "",
        ).joinToString("\n")

        val words = CaptionSentenceMergePolicy.extractWords(vtt)

        assertEquals(listOf("あいうえお。", "かきくけこ。"), words.map { it.text })
        assertEquals(0L, words[0].startMs)
        assertEquals(5_000L, words[0].endMs)
        assertEquals(5_000L, words[1].startMs)
        assertEquals(10_000L, words[1].endMs)
    }

    @Test
    fun doesNotCutOffAShortLeadingWordFromALongUntimedBlock() {
        // 一个很短的词（"今"）后面紧跟一整块长文字：以前会在"今"后面切开，得到只有一个字的碎片行。
        val block = "語".repeat(100)
        val vtt = listOf(
            "WEBVTT",
            "",
            "00:00:00.000 --> 00:00:20.000",
            "今<00:00:01.000><c>$block。</c>",
            "",
        ).joinToString("\n")
        val rows = listOf(SubLine(0, 0L, 20_000L, "今$block。"))

        val result = CaptionSentenceMergePolicy.merge(rows, vtt, minTimedTags = 1, minRows = 1)

        val lines = result.lines
        assertNotNull(result.reason, lines)
        assertEquals(1, lines!!.size)
        assertEquals("今$block。", lines[0].text)
        assertEquals(0L, lines[0].startMs)
    }

    @Test
    fun prefersCuttingAfterAJapaneseClauseEndingOverTheExactMiddle() {
        // 14 个词，每个 6 个单位，共 84，超过一句的上限。正中间在第 7 个词后；
        // 但整句只有第 9 个词以"て"结尾，所以应在它后面切（而不是切在正中间的词中间）。
        val tokens = (0..13).map { index ->
            when (index) {
                8 -> "語語語語語て"
                13 -> "語語語語語語。"
                else -> "語語語語語語"
            }
        }
        val line = StringBuilder(tokens[0])
        for (i in 1..13) {
            line.append("<00:00:").append(i.toString().padStart(2, '0')).append(".000><c>")
                .append(tokens[i]).append("</c>")
        }
        val vtt = listOf(
            "WEBVTT",
            "",
            "00:00:00.000 --> 00:00:20.000",
            line.toString(),
            "",
        ).joinToString("\n")
        val rows = listOf(SubLine(0, 0L, 20_000L, tokens.joinToString("")))

        val result = CaptionSentenceMergePolicy.merge(rows, vtt, minTimedTags = 5, minRows = 1)

        val lines = result.lines
        assertNotNull(result.reason, lines)
        assertEquals(2, lines!!.size)
        assertTrue(lines[0].text.endsWith("て"))
        assertEquals(53, lines[0].text.count { it == '語' })
        assertEquals(30, lines[1].text.count { it == '語' })
        assertEquals(9_000L, lines[0].endMs)
        assertEquals(9_000L, lines[1].startMs)
    }
}
