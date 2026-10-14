package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedTextCueXmlParserTest {
    @Test
    fun extractsDifferentDurationsRegardlessOfAttributeOrderOrQuoteStyle() {
        val xml = """
            <transcript>
              <text start="1.2" dur="3.4">First line</text>
              <text dur='0.75' start='5.0'>Second line</text>
              <text start="8.1">Third line</text>
            </transcript>
        """.trimIndent()

        val cues = TimedTextCueXmlParser.parse(xml)

        assertEquals(3, cues.size)
        assertEquals(TimedTextCueXmlParser.Cue(1_200L, 4_600L, "First line"), cues[0])
        assertEquals(TimedTextCueXmlParser.Cue(5_000L, 5_750L, "Second line"), cues[1])
        assertEquals(TimedTextCueXmlParser.Cue(8_100L, 11_100L, "Third line"), cues[2])
    }

    @Test
    fun skipsInvalidTimingAndEmptyCueBodies() {
        val xml = """
            <transcript>
              <text start="NaN" dur="1">bad start</text>
              <text start="2" dur="-1">bad duration</text>
              <text start="3" dur="1">   </text>
              <text start="4" dur="1">valid</text>
            </transcript>
        """.trimIndent()

        val cues = TimedTextCueXmlParser.parse(xml)

        assertEquals(1, cues.size)
        assertEquals(4_000L, cues.single().startMs)
        assertEquals(5_000L, cues.single().endMs)
        assertTrue(cues.single().rawText == "valid")
    }

    @Test
    fun requiresTimedTextTranscriptRoot() {
        assertTrue(TimedTextCueXmlParser.parse("<root><text start=\"1\" dur=\"2\">x</text></root>").isEmpty())
    }
}
