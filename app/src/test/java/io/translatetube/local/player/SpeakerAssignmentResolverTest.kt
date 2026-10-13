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
}
