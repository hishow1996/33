package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAssignmentPolicyTest {
    @Test
    fun canonicalIdsFollowFirstAppearanceInsteadOfRawModelLabels() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(91, 0L, 900L),
            SpeakerAssignmentPolicy.SpeakerObservation(4, 1_800L, 2_400L),
            SpeakerAssignmentPolicy.SpeakerObservation(91, 3_000L, 3_600L),
            SpeakerAssignmentPolicy.SpeakerObservation(27, 4_000L, 4_900L),
        )

        assertEquals(
            mapOf(91 to 0, 4 to 1, 27 to 2),
            SpeakerAssignmentPolicy.canonicalSpeakerIds(observations),
        )
    }

    @Test
    fun differentRawLabelsWithTheSameTimelineProduceTheSameVoiceOrder() {
        val firstRun = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(91, 0L, 900L),
            SpeakerAssignmentPolicy.SpeakerObservation(4, 1_800L, 2_400L),
            SpeakerAssignmentPolicy.SpeakerObservation(91, 3_000L, 3_600L),
            SpeakerAssignmentPolicy.SpeakerObservation(27, 4_000L, 4_900L),
        )
        val secondRun = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(2, 0L, 900L),
            SpeakerAssignmentPolicy.SpeakerObservation(78, 1_800L, 2_400L),
            SpeakerAssignmentPolicy.SpeakerObservation(2, 3_000L, 3_600L),
            SpeakerAssignmentPolicy.SpeakerObservation(100, 4_000L, 4_900L),
        )

        fun cueVoiceOrder(
            rows: List<SpeakerAssignmentPolicy.SpeakerObservation>,
            canonical: Map<Int, Int>,
        ): List<Int> = rows.mapNotNull { canonical[it.speakerId] }

        assertEquals(
            cueVoiceOrder(firstRun, SpeakerAssignmentPolicy.canonicalSpeakerIds(firstRun)),
            cueVoiceOrder(secondRun, SpeakerAssignmentPolicy.canonicalSpeakerIds(secondRun)),
        )
    }

    @Test
    fun repeatedObservationsUseEarliestTimestampEvenWhenInputIsUnsorted() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(8, 5_000L, 5_600L),
            SpeakerAssignmentPolicy.SpeakerObservation(13, 2_000L, 2_500L),
            SpeakerAssignmentPolicy.SpeakerObservation(8, 1_000L, 1_800L),
        )

        assertEquals(
            mapOf(8 to 0, 13 to 1),
            SpeakerAssignmentPolicy.canonicalSpeakerIds(observations),
        )
    }

    @Test
    fun invalidObservationsAndUnknownSpeakersAreIgnored() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(-1, 0L, 100L),
            SpeakerAssignmentPolicy.SpeakerObservation(3, -1L, 100L),
            SpeakerAssignmentPolicy.SpeakerObservation(4, 300L, 300L),
        )

        assertEquals(emptyMap<Int, Int>(), SpeakerAssignmentPolicy.canonicalSpeakerIds(observations))
    }
}
