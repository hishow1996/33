package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class RegexScanTest {
    @Test
    fun countsEveryMatchInALongInput() {
        val body = buildString {
            repeat(50_000) { append("word<00:00:01.200> ") }
        }
        assertEquals(50_000, RegexScan.count(Regex("""<\d{2,}:\d{2}:\d{2}[.,]\d{1,6}>"""), body))
        assertEquals(50_000, SubtitleParsers.countWordTimingTags(body))
    }

    @Test
    fun forEachMatchGivesIndexAndGroups() {
        val seen = ArrayList<Pair<Int, String>>()
        RegexScan.forEachMatch(Regex("""(\d+)-"""), "1-22-333-") { index, matcher ->
            seen += index to matcher.group(1).orEmpty()
        }
        assertEquals(listOf(0 to "1", 1 to "22", 2 to "333"), seen)
    }

    @Test
    fun noMatchGivesZeroAndNoCallbacks() {
        assertEquals(0, RegexScan.count(Regex("""x"""), "abc"))
        var called = false
        RegexScan.forEachMatch(Regex("""x"""), "abc") { _, _ -> called = true }
        assertEquals(false, called)
    }

    @Test
    fun timedTextXmlWithManyCuesStillParsesInOrder() {
        val xml = buildString {
            append("<transcript>")
            repeat(3_000) { append("<text start=\"$it.0\" dur=\"0.9\">line $it</text>") }
            append("</transcript>")
        }
        val lines = SubtitleParsers.parseTimedTextXml(xml)
        assertEquals(3_000, lines.size)
        assertEquals("line 2999", lines.last().text)
    }
}
