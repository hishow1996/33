package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAssignmentPolicyTest {
    @Test
    fun observationsFromSegmentsPreserveTimestampsAndModelConfidence() {
        val observations = SpeakerAssignmentPolicy.observationsFromSegments(
            listOf(SpeakerDiarizer.Segment(0.25f, 0.75f, 12, 0.87f))
        )

        assertEquals(1, observations.size)
        assertEquals(12, observations.single().speakerId)
        assertEquals(250L, observations.single().startMs)
        assertEquals(750L, observations.single().endMs)
        assertEquals(0.87f, observations.single().confidence, 0.0001f)
    }

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

    @Test
    fun cueAttributionChoosesTheSpeakerWithTheLargestOverlap() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(9, 0L, 400L, 0.99f),
            SpeakerAssignmentPolicy.SpeakerObservation(2, 100L, 900L, 0.30f),
        )

        assertEquals(2, SpeakerAssignmentPolicy.bestSpeakerForCue(observations, 0L, 1_000L, 200L))
    }

    @Test
    fun nearEqualOverlapsWithoutClearConfidenceLeadRemainUnassigned() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(7, 0L, 500L, 0.91f),
            SpeakerAssignmentPolicy.SpeakerObservation(3, 0L, 500L, 0.89f),
            SpeakerAssignmentPolicy.SpeakerObservation(1, 0L, 500L, 0.90f),
        )

        assertEquals(-1, SpeakerAssignmentPolicy.bestSpeakerForCue(observations, 0L, 700L, 200L))
    }

    @Test
    fun strongConfidenceLeadCanResolveAnEqualOverlap() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(7, 0L, 500L, 0.45f),
            SpeakerAssignmentPolicy.SpeakerObservation(3, 0L, 500L, 0.95f),
        )

        assertEquals(3, SpeakerAssignmentPolicy.bestSpeakerForCue(observations, 0L, 700L, 200L))
    }

    @Test
    fun clearOverlapWinnerIsStillAssignedEvenWhenConfidenceIsSimilar() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(7, 0L, 700L, 0.90f),
            SpeakerAssignmentPolicy.SpeakerObservation(3, 400L, 700L, 0.89f),
        )

        assertEquals(7, SpeakerAssignmentPolicy.bestSpeakerForCue(observations, 0L, 1_000L, 200L))
    }

    @Test
    fun cueAttributionRejectsBriefOverlapAndInvalidCueRanges() {
        val observations = listOf(SpeakerAssignmentPolicy.SpeakerObservation(5, 0L, 50L, 0.9f))

        assertEquals(-1, SpeakerAssignmentPolicy.bestSpeakerForCue(observations, 0L, 1_000L, 100L))
        assertEquals(-1, SpeakerAssignmentPolicy.bestSpeakerForCue(observations, -1L, 1_000L, 0L))
        assertEquals(-1, SpeakerAssignmentPolicy.bestSpeakerForCue(observations, 1_000L, 1_000L, 0L))
    }

    @Test
    fun minimumOverlapUsesARealFloorForVeryShortCues() {
        assertEquals(40L, SpeakerAssignmentPolicy.minimumOverlapMsForCue(100L))
        assertEquals(100L, SpeakerAssignmentPolicy.minimumOverlapMsForCue(500L))
        assertEquals(250L, SpeakerAssignmentPolicy.minimumOverlapMsForCue(5_000L))
        assertEquals(40L, SpeakerAssignmentPolicy.minimumOverlapMsForCue(0L))
    }

    @Test
    fun duplicateAndUnorderedSegmentsDoNotAmplifyOneSpeakersConfidenceScoreByOrder() {
        val firstOrder = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(8, 0L, 700L, 0.30f),
            SpeakerAssignmentPolicy.SpeakerObservation(8, 300L, 1_000L, 0.90f),
            SpeakerAssignmentPolicy.SpeakerObservation(3, 0L, 600L, 0.60f),
        )
        val reversed = firstOrder.reversed()

        assertEquals(
            SpeakerAssignmentPolicy.bestSpeakerForCue(firstOrder, 0L, 1_000L, 200L),
            SpeakerAssignmentPolicy.bestSpeakerForCue(reversed, 0L, 1_000L, 200L),
        )
    }

    @Test
    fun previousTimelineKeepsPersonIdsWhenFirstAppearanceOrderChanges() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(42, 0L, 900L, 0.95f),
            SpeakerAssignmentPolicy.SpeakerObservation(7, 1_000L, 1_900L, 0.95f),
            SpeakerAssignmentPolicy.SpeakerObservation(42, 2_000L, 2_900L, 0.95f),
            SpeakerAssignmentPolicy.SpeakerObservation(7, 3_000L, 3_900L, 0.95f),
        )
        val anchors = listOf(
            SpeakerAssignmentPolicy.CueAnchor(0L, 900L, 1),
            SpeakerAssignmentPolicy.CueAnchor(1_000L, 1_900L, 0),
            SpeakerAssignmentPolicy.CueAnchor(2_000L, 2_900L, 1),
            SpeakerAssignmentPolicy.CueAnchor(3_000L, 3_900L, 0),
        )

        assertEquals(
            mapOf(42 to 1, 7 to 0),
            SpeakerAssignmentPolicy.stableSpeakerIds(observations, anchors),
        )
    }

    @Test
    fun conflictingPreviousPeopleDoNotForceAnIdentityMatch() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(42, 0L, 900L, 0.95f),
            SpeakerAssignmentPolicy.SpeakerObservation(7, 1_000L, 1_900L, 0.95f),
            SpeakerAssignmentPolicy.SpeakerObservation(42, 2_000L, 2_900L, 0.95f),
        )
        val anchors = listOf(
            SpeakerAssignmentPolicy.CueAnchor(0L, 900L, 3),
            SpeakerAssignmentPolicy.CueAnchor(1_000L, 1_900L, 4),
            SpeakerAssignmentPolicy.CueAnchor(2_000L, 2_900L, 4),
        )

        assertEquals(
            mapOf(42 to 0, 7 to 1),
            SpeakerAssignmentPolicy.stableSpeakerIds(observations, anchors),
        )
    }

    @Test
    fun previousPersonIdCanBeInheritedByOnlyOneNewCluster() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(42, 0L, 1_500L, 0.95f),
            SpeakerAssignmentPolicy.SpeakerObservation(7, 2_000L, 3_500L, 0.95f),
        )
        val anchors = listOf(
            SpeakerAssignmentPolicy.CueAnchor(0L, 1_500L, 4),
            SpeakerAssignmentPolicy.CueAnchor(2_000L, 3_500L, 4),
        )

        val stable = SpeakerAssignmentPolicy.stableSpeakerIds(observations, anchors)

        assertEquals(2, stable.values.toSet().size)
        assertEquals(1, stable.values.count { it == 4 })
        assertEquals(true, stable.values.any { it > 4 })
    }

    @Test
    fun canonicalSpeakerDurationAggregationDoesNotOverflowLong() {
        val observations = listOf(
            SpeakerAssignmentPolicy.SpeakerObservation(1, 0L, Long.MAX_VALUE),
            SpeakerAssignmentPolicy.SpeakerObservation(1, 0L, Long.MAX_VALUE),
            SpeakerAssignmentPolicy.SpeakerObservation(2, 0L, Long.MAX_VALUE),
        )

        assertEquals(
            mapOf(1 to 0, 2 to 1),
            SpeakerAssignmentPolicy.canonicalSpeakerIds(observations),
        )
    }
}
