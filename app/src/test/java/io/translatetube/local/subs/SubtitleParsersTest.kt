package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParsersTest {
    @Test
    fun decodesHtmlEntitiesWithoutTreatingDecodedAngleBracketsAsMarkup() {
        val source = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            Tom &amp; Jerry: &quot;yes&quot; &#39;ok&#39; &lt;literal&gt; &nbsp; &#x1F600;
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(1, parsed.size)
        assertEquals("Tom & Jerry: \"yes\" 'ok' <literal> 😀", parsed.single().text)
    }

    @Test
    fun noteBlocksBetweenCuesAreNotAppendedToSubtitleText() {
        val source = """
            WEBVTT

            00:00:00.000 --> 00:00:01.000
            first cue

            NOTE translator metadata
            this comment must not become spoken subtitle text

            00:00:01.000 --> 00:00:02.000
            second cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf("first cue", "second cue"), parsed.map { it.text })
    }

    @Test
    fun orphanTextAfterBlankCueBoundaryIsNotAttachedToPreviousCue() {
        val source = """
            WEBVTT

            00:00:00.000 --> 00:00:01.000
            first cue

            text without a timestamp

            00:00:01.000 --> 00:00:02.000
            second cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf("first cue", "second cue"), parsed.map { it.text })
    }

    @Test
    fun vttExportSkipsEmptyAndInvalidTimeWindowsAndSortsCues() {
        val exported = SubtitleParsers.toVtt(
            listOf(
                SubLine(0, 2_000L, 1_000L, "reversed"),
                SubLine(1, -1L, 500L, "negative start"),
                SubLine(2, 1_000L, 2_000L, "valid"),
                SubLine(3, 0L, 1L, "   "),
                SubLine(4, 500L, 750L, "early"),
            ),
        )

        org.junit.Assert.assertTrue(exported.contains("valid"))
        org.junit.Assert.assertFalse(exported.contains("reversed"))
        org.junit.Assert.assertFalse(exported.contains("negative start"))
        org.junit.Assert.assertTrue(exported.indexOf("early") < exported.indexOf("valid"))
    }

    @Test
    fun skipsAssCueWhoseTimestampRoundsPastLongRange() {
        val source = """
            [Events]
            Dialogue: 0,2562047788015:12:55.808,2562047788015:12:56.000,Default,,0,0,0,,overflowing timestamp
        """.trimIndent()

        assertEquals(emptyList<SubLine>(), SubtitleParsers.parseAss(source))
    }

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
    fun rejectsMalformedExplicitTimedTextDurationInsteadOfInventingDefault() {
        assertEquals(null, SubtitleParsers.parseTimedTextTiming("start=\"1.0\" dur=\"NaN\""))
        assertEquals(null, SubtitleParsers.parseTimedTextTiming("start=\"1.0\" dur=\"-2\""))
        assertEquals(null, SubtitleParsers.parseTimedTextTiming("start=\"1.0\" dur=\"\""))
        assertEquals(1_000L to 4_000L, SubtitleParsers.parseTimedTextTiming("start=\"1.0\""))
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

    @Test
    fun skipsMalformedWebVttTimesButKeepsFollowingValidCues() {
        val source = """
            WEBVTT

            00:99:99.000 --> 00:99:59.000
            invalid minutes and seconds

            00:00:01.000 --> 00:00:02.000
            valid cue

            01:00:00.000 --> 01:00:01.000
            long video cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf("valid cue", "long video cue"), parsed.map { it.text })
        assertEquals(listOf(1_000L, 3_600_000L), parsed.map { it.startMs })
        assertEquals(listOf(2_000L, 3_601_000L), parsed.map { it.endMs })
    }

    @Test
    fun acceptsThreeDigitHoursInLongWebVttVideos() {
        val source = """
            WEBVTT

            100:00:00.000 --> 100:00:01.000
            long video cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(1, parsed.size)
        assertEquals(360_000_000L, parsed.single().startMs)
        assertEquals(360_001_000L, parsed.single().endMs)
    }


    @Test
    fun largeSubtitleParsingHonorsCancellationBetweenInputChunks() {
        fun timestamp(totalSeconds: Int): String {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "00:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.000"
        }
        val source = buildString {
            appendLine("WEBVTT")
            appendLine()
            repeat(400) { index ->
                appendLine("${timestamp(index)} --> ${timestamp(index + 1)}")
                appendLine("subtitle $index")
                appendLine()
            }
        }
        var checks = 0
        val error = runCatching {
            SubtitleParsers.parseAutoCancellable(source) {
                checks++
                if (checks == 3) throw java.util.concurrent.CancellationException("subtitle parsing cancelled")
            }
        }.exceptionOrNull()

        org.junit.Assert.assertTrue(error is java.util.concurrent.CancellationException)
        org.junit.Assert.assertEquals(3, checks)
    }

    @Test
    fun acceptsSubtitleTimestampFractionsOfDifferentWidths() {
        val source = """
            WEBVTT

            00:00:01,2 --> 00:00:02,25
            short fractional widths

            00:00:03.1250 --> 00:00:04.1250
            extended fractional widths
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf(1_200L, 2_250L, 3_125L, 4_125L), parsed.flatMap { listOf(it.startMs, it.endMs) })
        assertEquals(listOf("short fractional widths", "extended fractional widths"), parsed.map { it.text })
    }

    @Test
    fun assParserAcceptsIndentedCaseInsensitiveDialogueKeywords() {
        val source = """
              dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,lowercase cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseAss(source)

        assertEquals(1, parsed.size)
        assertEquals("lowercase cue", parsed.single().text)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(2_000L, parsed.single().endMs)
    }

    @Test
    fun malformedExplicitTtmlEndDoesNotSilentlyFallBackToDuration() {
        val source = """
            <tt><body>
              <p begin="1s" end="broken" dur="2s">bad timing</p>
              <p begin="3s" dur="1s">valid cue</p>
            </body></tt>
        """.trimIndent()

        val parsed = SubtitleParsers.parseTtml(source)

        assertEquals(1, parsed.size)
        assertEquals("valid cue", parsed.single().text)
        assertEquals(3_000L, parsed.single().startMs)
        assertEquals(4_000L, parsed.single().endMs)
    }
}
