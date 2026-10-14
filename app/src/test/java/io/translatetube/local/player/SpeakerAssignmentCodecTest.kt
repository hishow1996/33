package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerAssignmentCodecTest {
    @Test
    fun roundTripRetainsPersonVoicesAndPerCueOverrides() {
        val expected = SpeakerAssignmentSnapshot(
            speakerVoices = mapOf(0 to -1, 2 to 14, 7 to 42),
            lineVoices = mapOf("0:1600" to -1, "1600:3400" to 42),
            lineOverrides = mapOf("1600:3400" to 13),
        )

        assertEquals(expected, SpeakerAssignmentCodec.decode(SpeakerAssignmentCodec.encode(expected)))
    }

    @Test
    fun invalidOrLegacyPayloadsAreIgnoredSafely() {
        assertEquals(SpeakerAssignmentSnapshot(), SpeakerAssignmentCodec.decode(null))
        assertEquals(SpeakerAssignmentSnapshot(), SpeakerAssignmentCodec.decode("old-format|s:1=8"))
        val decoded = SpeakerAssignmentCodec.decode(
            "speaker-map-v1|s:999=2|s:2=99|v:100:0=2|m:0:100=-2|x:4=3|s:3=12"
        )
        assertEquals(mapOf(3 to 12), decoded.speakerVoices)
        assertTrue(decoded.lineVoices.isEmpty())
        assertTrue(decoded.lineOverrides.isEmpty())
    }

    @Test
    fun lineKeyUsesBothCueBoundaries() {
        assertEquals("1200:2400", SpeakerAssignmentCodec.lineKey(1200L, 2400L))
        assertTrue(
            SpeakerAssignmentCodec.decode(
                SpeakerAssignmentCodec.encode(
                    SpeakerAssignmentSnapshot(lineOverrides = mapOf("1200:2400" to 9))
                )
            ).lineOverrides.containsKey("1200:2400")
        )
    }
    @Test
    fun roundTripRetainsVoiceIdsAboveTheOld54VoiceLimit() {
        val expected = SpeakerAssignmentSnapshot(
            speakerVoices = mapOf(0 to -1, 3 to 102),
            lineVoices = mapOf("0:1000" to 102),
            lineOverrides = mapOf("1000:2000" to 101),
        )

        assertEquals(expected, SpeakerAssignmentCodec.decode(SpeakerAssignmentCodec.encode(expected)))
    }

}
