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
}
