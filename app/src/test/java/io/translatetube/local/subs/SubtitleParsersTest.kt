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
    fun ignoresTimestampSubstringsEmbeddedInMalformedCueLines() {
        val source = """
            WEBVTT

            junk 00:00:01.000 --> 00:00:02.000
            this must not become a timed cue

            00:00:03.000 --> 00:00:04.000
            valid cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf("valid cue"), parsed.map { it.text })
    }

    @Test
    fun negativeTimesFormatAsZeroInsteadOfNegativeClockFields() {
        assertEquals("00:00:00", SubtitleParsers.fmtTime(-1L))
        assertEquals("00:00:00", SubtitleParsers.fmtTime(-1_000L))
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
        val source = "    dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,lowercase cue"

        val parsed = SubtitleParsers.parseAss(source)

        assertEquals(1, parsed.size)
        assertEquals("lowercase cue", parsed.single().text)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(2_000L, parsed.single().endMs)
    }

    @Test
    fun autoDetectsIndentedLowercaseAssDialogueLines() {
        val source = """
            |    dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,lowercase cue
            |DIALOGUE: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,uppercase cue
        """.trimMargin()

        val parsed = SubtitleParsers.parseAuto(source)

        assertEquals(listOf("lowercase cue", "uppercase cue"), parsed.map { it.text })
        assertEquals(listOf(1_000L, 3_000L), parsed.map { it.startMs })
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

    @Test
    fun ttmlAttributesPlaceholdersAndExportTimesIgnoreTheDefaultLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            val parsed = SubtitleParsers.parseTtml(
                """<TT><BODY><P BEGIN="1s" END="2s">upper-case timing</P></BODY></TT>"""
            )
            assertEquals(1, parsed.size)
            assertEquals(1_000L, parsed.single().startMs)
            assertEquals(2_000L, parsed.single().endMs)

            assertEquals(true, SubtitleParsers.isPlaceholderTranslation("TRANSLATING"))
            assertEquals("00:01:01", SubtitleParsers.fmtTime(61_000L))
            val exported = SubtitleParsers.toVtt(listOf(SubLine(0, 1_000L, 2_000L, "cue")))
            assertEquals(true, exported.contains("00:00:01.000 --> 00:00:02.000"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }


    @Test
    fun srtExportSortsCuesRenumbersThemAndSkipsInvalidWindows() {
        val exported = SubtitleParsers.toSrt(
            listOf(
                SubLine(8, 2_000L, 3_500L, "Hello", "你好"),
                SubLine(9, 4_000L, 3_000L, "invalid", "无效"),
                SubLine(2, 0L, 1_000L, "Untranslated", "正在翻译"),
                SubLine(3, -1L, 500L, "negative", "负数"),
            ),
        )

        org.junit.Assert.assertTrue(exported.startsWith(
            "1\n00:00:00,000 --> 00:00:01,000\nUntranslated\n\n" +
                "2\n00:00:02,000 --> 00:00:03,500\n你好\n",
        ))
        org.junit.Assert.assertFalse(exported.contains("invalid"))
        org.junit.Assert.assertFalse(exported.contains("negative"))
    }

    @Test
    fun bilingualExportsPlaceChineseAboveOriginalAndRetainCueTiming() {
        val rows = listOf(SubLine(0, 1_200L, 2_400L, "Hello there", "你好"))

        assertEquals(
            "1\n00:00:01,200 --> 00:00:02,400\n你好\nHello there\n\n",
            SubtitleParsers.toSrt(rows, bilingual = true),
        )

        val vtt = SubtitleParsers.toBilingualVtt(rows)
        org.junit.Assert.assertTrue(vtt.startsWith(
            "WEBVTT\n\n00:00:01.200 --> 00:00:02.400\n你好\nHello there\n",
        ))
    }

    @Test
    fun bilingualExportDoesNotRepeatTextWhenTranslationMatchesSource() {
        val rows = listOf(SubLine(0, 0L, 1_000L, "你好", "你好"))
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,000\n你好\n\n",
            SubtitleParsers.toSrt(rows, bilingual = true),
        )
    }


    @Test
    fun preservesExplicitHtmlLineBreaksInsideWebVttCues() {
        val parsed = SubtitleParsers.parseVtt(
            "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello<br>你好<br />世界\n",
        )
        assertEquals(1, parsed.size)
        assertEquals("Hello\n你好\n世界", parsed.single().text)
    }

    @Test
    fun bilingualExportsAvoidDuplicateRowsWhenOnlyTrailingPunctuationDiffers() {
        val row = listOf(SubLine(0, 0L, 1_000L, "你好！", "你好"))
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,000\n你好\n\n",
            SubtitleParsers.toSrt(row, bilingual = true),
        )
        assertEquals(
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n你好\n\n",
            SubtitleParsers.toBilingualVtt(row),
        )
    }


    @Test
    fun assParserHonorsCustomEventFormatAndPreservesCommasInText() {
        val source = """
            [Events]
            Format: Layer, End, Start, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:04.00,0:00:01.00,Default,Speaker,0,0,0,,Hello, world, with commas
        """.trimIndent()

        val parsed = SubtitleParsers.parseAss(source)

        assertEquals(1, parsed.size)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(4_000L, parsed.single().endMs)
        assertEquals("Hello, world, with commas", parsed.single().text)
    }

    @Test
    fun assParserConvertsHardSpacesAndPreservesExplicitLineBreaks() {
        val source = """
            [Events]
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,one\htwo\Nthree
        """.trimIndent()

        val parsed = SubtitleParsers.parseAss(source)

        assertEquals("one two\nthree", parsed.single().text)
    }

    @Test
    fun malformedAssFormatFallsBackToTheStandardEventLayout() {
        val source = """
            [Events]
            Format: Layer, End, Text, Start
            Dialogue: 0,0:00:04.00,unusable format,0:00:01.00
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,standard fallback
        """.trimIndent()

        val parsed = SubtitleParsers.parseAss(source)

        assertEquals(1, parsed.size)
        assertEquals("standard fallback", parsed.single().text)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(2_000L, parsed.single().endMs)
    }


    @Test
    fun subtitleExportsKeepHoursBeyondTwoDigitsWithoutLocaleFormatting() {
        val startMs = 123L * 3_600_000L + 5_006L
        val rows = listOf(SubLine(0, startMs, startMs + 1_000L, "original", "译文"))

        assertEquals(
            "1\n123:00:05,006 --> 123:00:06,006\n译文\n\n",
            SubtitleParsers.toSrt(rows),
        )
        assertEquals(
            "WEBVTT\n\n123:00:05.006 --> 123:00:06.006\noriginal\n\n",
            SubtitleParsers.toVtt(rows),
        )
    }

    @Test
    fun ttmlNormalizesInlineSpacesWithoutCollapsingExplicitLineBreaks() {
        val source = "<tt><body><div><p begin=\"1s\" end=\"3s\">one    two<br/>three\t\tfour</p></div></body></tt>"

        val parsed = SubtitleParsers.parseTtml(source)

        assertEquals(1, parsed.size)
        assertEquals("one two\nthree four", parsed.single().text)
    }

    @Test
    fun detectsTranslationPlaceholdersAndProgressWithoutTreatingRealTextAsPlaceholder() {
        assertTrue(SubtitleParsers.isPlaceholderTranslation(" 正在翻译 "))
        assertTrue(SubtitleParsers.isPlaceholderTranslation("翻译中 3/5 行"))
        assertTrue(SubtitleParsers.isPlaceholderTranslation("TRANSLATING"))
        assertTrue(SubtitleParsers.isPlaceholderTranslation("译文暂不可用"))
        org.junit.Assert.assertFalse(SubtitleParsers.isPlaceholderTranslation("翻译完成，效果很好"))
        org.junit.Assert.assertFalse(SubtitleParsers.isPlaceholderTranslation("The translating machine"))
        org.junit.Assert.assertFalse(SubtitleParsers.isPlaceholderTranslation(null))
    }


    @Test
    fun timedTextXmlPreservesExplicitHtmlLineBreaks() {
        val source = """<transcript><text start="1.0" dur="2.0">first<br/>second<br>third</text></transcript>"""

        val parsed = SubtitleParsers.parseTimedTextXml(source)

        assertEquals(1, parsed.size)
        assertEquals("first\nsecond\nthird", parsed.single().text)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(3_000L, parsed.single().endMs)
    }



    @Test
    fun decodesLatinAccentsInWebCaptionsWithCorrectEntityCase() {
        val source = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            Caf&eacute; &Eacute; &ntilde; &uuml; &szlig; &#x1F600;
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(1, parsed.size)
        assertEquals("Café É ñ ü ß 😀", parsed.single().text)
    }


    @Test
    fun replacesInvalidNumericCodePointsAndKeepsUnknownNamedEntities() {
        val decoded = SubtitleTextDecoder.decode("zero &#0; surrogate &#xD800; out-of-range &#x110000; unknown &MadeUpEntity;")

        assertEquals(
            "zero \uFFFD surrogate \uFFFD out-of-range \uFFFD unknown &MadeUpEntity;",
            decoded,
        )
    }

    @Test
    fun decoderHandlesUppercaseHexPrefixAndLargeValidCodePoint() {
        assertEquals("🚀", SubtitleTextDecoder.decode("&#X1F680;"))
        assertEquals("É", SubtitleTextDecoder.decode("&Eacute;"))
        assertEquals("é", SubtitleTextDecoder.decode("&eacute;"))
    }


    @Test
    fun parsesNamespacePrefixedTtmlElementsAndLineBreaks() {
        val source = """<?xml version="1.0"?><tt:tt xmlns:tt="http://www.w3.org/ns/ttml"><tt:body><tt:div><tt:p begin="1s" end="3s">first<tt:br/>second</tt:p></tt:div></tt:body></tt:tt>"""

        val parsed = SubtitleParsers.parseAuto(source)

        assertEquals(1, parsed.size)
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(3_000L, parsed.single().endMs)
        assertEquals("first\nsecond", parsed.single().text)
    }

    @Test
    fun webVttExportEscapesLiteralMarkupAndRoundTripsSpecialCharacters() {
        val rows = listOf(SubLine(0, 0L, 1_000L, "Use <b>literal</b> & quotes"))

        val exported = SubtitleParsers.toVtt(rows)

        assertEquals(
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nUse &lt;b&gt;literal&lt;/b&gt; &amp; quotes\n\n",
            exported,
        )
        assertEquals("Use <b>literal</b> & quotes", SubtitleParsers.parseVtt(exported).single().text)
    }


    @Test
    fun preservesAngleBracketComparisonsAndStripsWebVttTimestampTags() {
        val source = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            A < 3 and B > 2

            00:00:02.000 --> 00:00:04.000
            <00:00:02.500>timed word
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf("A < 3 and B > 2", "timed word"), parsed.map { it.text })
    }


    @Test
    fun rejectsTrailingGarbageAfterWebVttEndTimestampButAllowsCueSettings() {
        val source = """
            WEBVTT

            00:00:00.000 --> 00:00:01.000suffix
            malformed cue

            00:00:01.000 --> 00:00:02.000 align:start
            valid cue
        """.trimIndent()

        val parsed = SubtitleParsers.parseVtt(source)

        assertEquals(listOf("valid cue"), parsed.map { it.text })
        assertEquals(1_000L, parsed.single().startMs)
        assertEquals(2_000L, parsed.single().endMs)
    }


    @Test
    fun plainTextCopySortsChronologicallyAndSkipsMalformedWindows() {
        val rows = listOf(
            SubLine(0, 2_000L, 3_000L, "later", "稍后"),
            SubLine(1, -100L, 500L, "invalid start", "错误"),
            SubLine(2, 0L, 1_000L, "Hello", "你好"),
            SubLine(3, 1_000L, 1_000L, "empty window", "空窗"),
            SubLine(4, 3_000L, 4_000L, "OK!", "OK"),
        )

        assertEquals(
            "你好\\nHello\\n\\n稍后\\nlater\\n\\nOK",
            SubtitleParsers.toPlainText(rows),
        )
    }

    @Test
    fun plainTextCopyCanEmitChineseOnlyAndFallsBackFromPlaceholderTranslation() {
        val rows = listOf(
            SubLine(0, 0L, 1_000L, "Hello", "译文暂不可用"),
            SubLine(1, 1_000L, 2_000L, "世界", "世界"),
        )

        assertEquals("Hello\\n\\n世界", SubtitleParsers.toPlainText(rows, bilingual = false))
    }

}
