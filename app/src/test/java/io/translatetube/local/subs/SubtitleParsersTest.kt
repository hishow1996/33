package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParsersTest {
    @Test
    fun skipsMalformedAssCueWithoutDiscardingValidCues() {
        val source = """
            [Events]
            Dialogue: 0,broken-time,0:00:02.00,Default,,0,0,0,,malformed
            Dialogue: 0,0:00:03.00,0:00:02.00,Default,,0,0,0,,reversed
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,valid, with comma
        """.trimIndent()

        val parsed = SubtitleParsers.parseAss(source)

        assertEquals(1, parsed.size)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(2_000L, parsed.single().endMs)
        assertEquals("valid, with comma", parsed.single().text)
    }

    @Test
    fun skipsWebVttCueWithNonPositiveTimeWindow() {
        val source = """
            WEBVTT

            00:00:03.000 --> 00:00:02.000
            invalid window

            00:00:00.000 --> 00:00:01.000
            valid cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(1, parsed.size)
        assertEquals("valid cue", parsed.single().text)
        assertEquals(0L, parsed.single().startMs)
        assertEquals(1_000L, parsed.single().endMs)
    }

    @Test
    fun sortsOutOfOrderWebVttCuesBeforeAssigningTimelineIndices() {
        val source = """
            WEBVTT

            00:00:02.000 --> 00:00:03.000
            third cue

            00:00:00.000 --> 00:00:01.000
            first cue

            00:00:01.000 --> 00:00:02.000
            second cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf("first cue", "second cue", "third cue"), parsed.map { it.text })
        assertEquals(listOf(0, 1, 2), parsed.map { it.index })
    }

    @Test
    fun sortsOutOfOrderAssCuesWhenUsingTheFormatSpecificParser() {
        val source = """
            [Events]
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,third cue
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,first cue
            Dialogue: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,second cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseAss(source)

        assertEquals(listOf("first cue", "second cue", "third cue"), parsed.map { it.text })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), parsed.map { it.startMs })
        assertEquals(listOf(0, 1, 2), parsed.map { it.index })
    }
    @Test
    fun timedTextXmlHonorsDurationRegardlessOfAttributeOrderOrQuoteStyle() {
        val source = """
            <transcript>
              <text start="2.5" dur="1.25">later cue</text>
              <text dur='0.5' start='1.25'>earlier cue</text>
            </transcript>
        """.trimIndent()

        val parsed = SubtitleParsers.parseTimedTextXml(source)

        assertEquals(listOf("earlier cue", "later cue"), parsed.map { it.text })
        assertEquals(listOf(1_250L, 2_500L), parsed.map { it.startMs })
        assertEquals(listOf(1_750L, 3_750L), parsed.map { it.endMs })
    }

    @Test
    fun rejectsInvalidAndOverflowingTtmlTimes() {
        assertEquals(null, SubtitleParsers.parseTtmlTime("NaNs"))
        assertEquals(null, SubtitleParsers.parseTtmlTime("Infinitys"))
        assertEquals(null, SubtitleParsers.parseTtmlTime("-0.5s"))
        assertEquals(null, SubtitleParsers.parseTtmlTime("1:60:00"))
        assertEquals(null, SubtitleParsers.parseTtmlTime("1:02:60"))
        assertEquals(null, SubtitleParsers.parseTtmlTime("1:2:3:4"))
        assertEquals(null, SubtitleParsers.parseTtmlTime("9223372036854776s"))
        assertEquals(1_250L, SubtitleParsers.parseTtmlTime("1.25s"))
        assertEquals(1_250L, SubtitleParsers.parseTtmlTime("1250ms"))
        assertEquals(3_723_500L, SubtitleParsers.parseTtmlTime("1:02:03.5"))
    }

    @Test
    fun preservesNumericOnlySubtitleTextWithoutTreatingItAsAnSrtIndex() {
        val webVtt = """
            WEBVTT

            00:00:00.000 --> 00:00:01.000
            2026

            00:00:01.000 --> 00:00:02.000
            123
        """.trimIndent()
        val srt = """
            1
            00:00:00,000 --> 00:00:01,000
            2026

            2
            00:00:01,000 --> 00:00:02,000
            123
        """.trimIndent()

        assertEquals(listOf("2026", "123"), SubtitleParsers.parseVtt(webVtt).map { it.text })
        assertEquals(listOf("2026", "123"), SubtitleParsers.parseSrt(srt).map { it.text })
    }

    @Test
    fun timedTextXmlTagAndAttributeMatchingIsCaseInsensitive() {
        val source = """
            <Transcript>
              <Text Start="1.0" Dur="2.0">upper case cue</Text>
            </Transcript>
        """.trimIndent()

        val parsed = SubtitleParsers.parseTimedTextXml(source)

        assertEquals(1, parsed.size)
        assertEquals("upper case cue", parsed.single().text)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(3_000L, parsed.single().endMs)
    }

}
