package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAssignmentResolverTest {
    @Test
    fun manualCueOverrideWinsOverAutomaticMapping() {
        val line = SubLine(2, 1_000, 2_000, "hello", "你好", speakerId = 4)
        val result = SpeakerAssignmentResolver.apply(
            listOf(line),
            SpeakerAssignmentSnapshot(
                lineVoices = mapOf("1000:2000" to 8),
                lineOverrides = mapOf("1000:2000" to 21),
            ),
        )
        assertEquals(21, result.single().speakerId)
    }

    @Test
    fun automaticVoiceMappingIsRestoredForAnExactCueTimeline() {
        val lines = listOf(
            SubLine(0, 0, 1_000, "one"),
            SubLine(1, 1_000, 2_500, "two"),
        )
        val result = SpeakerAssignmentResolver.apply(
            lines,
            SpeakerAssignmentSnapshot(lineVoices = mapOf("1000:2500" to 17)),
        )
        assertEquals(-1, result[0].speakerId)
        assertEquals(17, result[1].speakerId)
    }

    @Test
    fun unrelatedCueKeepsItsExistingVoice() {
        val line = SubLine(0, 1_000, 2_000, "hello", speakerId = 11)
        val result = SpeakerAssignmentResolver.apply(
            listOf(line),
            SpeakerAssignmentSnapshot(lineOverrides = mapOf("2000:3000" to 3)),
        )
        assertEquals(11, result.single().speakerId)
    }

    @Test
    fun lineKeysForPersonOnlyIncludesCuesWithThatCanonicalIdentity() {
        val lines = listOf(
            SubLine(0, 0, 1_000, "a"),
            SubLine(1, 1_000, 2_000, "b"),
            SubLine(2, 2_000, 3_000, "c"),
        )
        val assignments = SpeakerAssignmentSnapshot(
            lineSpeakers = mapOf("0:1000" to 1, "1000:2000" to 2, "2000:3000" to 1),
        )
        assertEquals(
            setOf("0:1000", "2000:3000"),
            SpeakerAssignmentResolver.lineKeysForPerson(lines, assignments, 1),
        )
    }

    @Test
    fun applyToneToPersonOverridesPerLineChoicesAndPersistsPersonDefault() {
        val initial = SpeakerAssignmentSnapshot(
            speakerVoices = mapOf(1 to 7, 2 to 9),
            lineVoices = mapOf("0:1000" to 7, "1000:2000" to 9, "2000:3000" to 7),
            lineOverrides = mapOf("0:1000" to 12, "1000:2000" to 14),
            lineSpeakers = mapOf("0:1000" to 1, "1000:2000" to 2, "2000:3000" to 1),
        )
        val result = SpeakerAssignmentResolver.applyToneToPerson(
            initial,
            personId = 1,
            voiceId = 21,
            lineKeys = setOf("0:1000", "2000:3000"),
        )
        assertEquals(21, result.speakerVoices[1])
        assertEquals(21, result.lineVoices["0:1000"])
        assertEquals(21, result.lineVoices["2000:3000"])
        assertEquals(false, result.lineOverrides.containsKey("0:1000"))
        assertEquals(14, result.lineOverrides["1000:2000"])
        assertEquals(2, result.lineSpeakers["1000:2000"])
    }
}
