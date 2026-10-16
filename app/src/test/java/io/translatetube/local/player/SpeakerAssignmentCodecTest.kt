package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "speaker-map-v2|s:999=2|s:2=99|v:100:0=2|m:0:100=-2|x:4=3|s:3=12"
        )
        assertEquals(mapOf(3 to 12), decoded.speakerVoices)
        assertTrue(decoded.lineVoices.isEmpty())
        assertTrue(decoded.lineOverrides.isEmpty())
    }

    @Test
    fun v1MigrationKeepsCueChoicesButDropsUnstableDiarizerIds() {
        val decoded = SpeakerAssignmentCodec.decode(
            "speaker-map-v1|s:0=14|s:1=8|v:1000:2000=12|m:2000:3000=21"
        )

        assertTrue(decoded.speakerVoices.isEmpty())
        assertEquals(mapOf("1000:2000" to 12), decoded.lineVoices)
        assertEquals(mapOf("2000:3000" to 21), decoded.lineOverrides)
        assertEquals("speaker-map-v2", SpeakerAssignmentCodec.encode(decoded).substringBefore('|'))
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
    fun hugeAssignmentMapsKeepTheMostRecentTimedCuesWithinThePersistenceBudget() {
        val voices = (0..10_000).associate { index ->
            val start = index.toLong() * 10L
            "$start:${start + 5L}" to (index % 54)
        }
        val decoded = SpeakerAssignmentCodec.decode(
            SpeakerAssignmentCodec.encode(SpeakerAssignmentSnapshot(lineVoices = voices))
        )

        assertEquals(10_000, decoded.lineVoices.size)
        assertFalse(decoded.lineVoices.containsKey("0:5"))
        assertTrue(decoded.lineVoices.containsKey("100000:100005"))
    }

    @Test
    fun oversizedOrExcessivelyFragmentedPayloadsAreRejectedBeforeParsing() {
        assertEquals(
            SpeakerAssignmentSnapshot(),
            SpeakerAssignmentCodec.decode("speaker-map-v2|" + "x".repeat(1_500_001)),
        )
        assertEquals(
            SpeakerAssignmentSnapshot(),
            SpeakerAssignmentCodec.decode(
                "speaker-map-v2|" + (0..21_000).joinToString("|") { "x" },
            ),
        )
    }
}
