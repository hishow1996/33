package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerChunkIdentityPolicyTest {
    @Test
    fun firstWindowAssignsIdsByFirstAppearance() {
        val result = SpeakerChunkIdentityPolicy.normalizeFirstWindow(
            listOf(
                SpeakerChunkIdentityPolicy.Segment(9, 1_000L, 2_000L),
                SpeakerChunkIdentityPolicy.Segment(2, 0L, 700L),
                SpeakerChunkIdentityPolicy.Segment(9, 2_000L, 3_000L),
            ),
        )
        assertEquals(listOf(1, 0, 1), result.segments.map { it.speakerId })
        assertEquals(
            listOf(0, 1, 1),
            result.segments.sortedBy { it.startMs }.map { it.speakerId },
        )
        assertEquals(2, result.nextSpeakerId)
    }

    @Test
    fun overlapLinksLocalIdsToExistingPeopleAndClipsDuplicateAudio() {
        val previous = listOf(
            SpeakerChunkIdentityPolicy.Segment(0, 10_000L, 12_000L),
            SpeakerChunkIdentityPolicy.Segment(1, 12_000L, 15_000L),
        )
        val current = listOf(
            SpeakerChunkIdentityPolicy.Segment(7, 10_000L, 12_000L),
            SpeakerChunkIdentityPolicy.Segment(4, 12_000L, 15_000L),
            SpeakerChunkIdentityPolicy.Segment(7, 15_000L, 18_000L),
            SpeakerChunkIdentityPolicy.Segment(4, 18_000L, 20_000L),
        )

        val result = SpeakerChunkIdentityPolicy.mergeWindow(
            previous = previous,
            current = current,
            overlapStartMs = 10_000L,
            overlapEndMs = 15_000L,
            nextSpeakerId = 2,
        )

        assertEquals(2, result.linkedSpeakerCount)
        assertEquals(2, result.segments.size)
        assertEquals(listOf(0, 1), result.segments.map { it.speakerId })
        assertEquals(listOf(15_000L, 18_000L), result.segments.map { it.startMs })
        assertEquals(2, result.nextSpeakerId)
    }

    @Test
    fun speakerWithoutReliableOverlapGetsANewIdInsteadOfAnUnsafeMerge() {
        val previous = listOf(SpeakerChunkIdentityPolicy.Segment(0, 10_000L, 12_000L))
        val current = listOf(
            SpeakerChunkIdentityPolicy.Segment(7, 12_000L, 14_000L),
        )
        val result = SpeakerChunkIdentityPolicy.mergeWindow(
            previous = previous,
            current = current,
            overlapStartMs = 10_000L,
            overlapEndMs = 12_000L,
            nextSpeakerId = 1,
        )

        assertEquals(0, result.linkedSpeakerCount)
        assertEquals(listOf(1), result.segments.map { it.speakerId })
        assertEquals(2, result.nextSpeakerId)
    }

    @Test
    fun ambiguousOverlapDoesNotMergeTwoCurrentVoicesIntoOnePreviousPerson() {
        val previous = listOf(
            SpeakerChunkIdentityPolicy.Segment(0, 10_000L, 12_000L),
        )
        val current = listOf(
            SpeakerChunkIdentityPolicy.Segment(7, 10_000L, 11_000L),
            SpeakerChunkIdentityPolicy.Segment(8, 11_000L, 12_000L),
            SpeakerChunkIdentityPolicy.Segment(7, 12_000L, 13_000L),
            SpeakerChunkIdentityPolicy.Segment(8, 13_000L, 14_000L),
        )
        val result = SpeakerChunkIdentityPolicy.mergeWindow(
            previous = previous,
            current = current,
            overlapStartMs = 10_000L,
            overlapEndMs = 12_000L,
            nextSpeakerId = 1,
        )

        assertEquals(0, result.linkedSpeakerCount)
        assertEquals(listOf(1, 2), result.segments.map { it.speakerId }.distinct())
        assertEquals(3, result.nextSpeakerId)
    }

    @Test
    fun segmentsFullyInsideTheOverlapAreNotDuplicated() {
        val previous = listOf(SpeakerChunkIdentityPolicy.Segment(0, 10_000L, 12_000L))
        val current = listOf(SpeakerChunkIdentityPolicy.Segment(5, 10_000L, 12_000L))
        val result = SpeakerChunkIdentityPolicy.mergeWindow(
            previous = previous,
            current = current,
            overlapStartMs = 10_000L,
            overlapEndMs = 12_000L,
            nextSpeakerId = 1,
        )
        assertTrue(result.segments.isEmpty())
        assertEquals(1, result.nextSpeakerId)
    }
}
