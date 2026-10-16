package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingChunkTransitionPolicyTest {
    @Test
    fun acceptsOnlyTheStillActiveSourceTrackGeneration() {
        assertTrue(DubbingChunkTransitionPolicy.canCommit(false, 7, 7, true))
        assertFalse(DubbingChunkTransitionPolicy.canCommit(true, 7, 7, true))
        assertFalse(DubbingChunkTransitionPolicy.canCommit(false, 8, 7, true))
        assertFalse(DubbingChunkTransitionPolicy.canCommit(false, 7, 7, false))
    }

    @Test
    fun prefetchMarkerLeavesAPlaybackRateAdjustedLeadWindow() {
        assertEquals(
            25_600,
            DubbingChunkTransitionPolicy.prefetchMarkerPosition(
                chunkSampleCount = 32_000,
                sampleRate = 16_000,
                playbackRate = 1.0f,
            ),
        )
        assertEquals(
            19_200,
            DubbingChunkTransitionPolicy.prefetchMarkerPosition(
                chunkSampleCount = 32_000,
                sampleRate = 16_000,
                playbackRate = 2.0f,
            ),
        )
    }

    @Test
    fun prefetchMarkerStaysInsideSmallBuffersAndAfterSkippedSamples() {
        assertEquals(
            1,
            DubbingChunkTransitionPolicy.prefetchMarkerPosition(
                chunkSampleCount = 2_000,
                sampleRate = 16_000,
                playbackRate = 1.0f,
            ),
        )
        assertEquals(
            8_001,
            DubbingChunkTransitionPolicy.prefetchMarkerPosition(
                chunkSampleCount = 10_000,
                sampleRate = 16_000,
                playbackRate = 1.0f,
                currentHeadPosition = 8_000,
            ),
        )
        assertEquals(
            1,
            DubbingChunkTransitionPolicy.prefetchMarkerPosition(
                chunkSampleCount = 1,
                sampleRate = 16_000,
                playbackRate = 1.0f,
            ),
        )
        assertEquals(
            0,
            DubbingChunkTransitionPolicy.prefetchMarkerPosition(0, 16_000, 1.0f),
        )
    }


    @Test
    fun markerCandidatesFallBackToBufferEndWhenEarlyMarkerIsUnsupported() {
        assertEquals(
            listOf(25_600, 32_000),
            DubbingChunkTransitionPolicy.markerPositionCandidates(
                chunkSampleCount = 32_000,
                sampleRate = 16_000,
                playbackRate = 1.0f,
            ),
        )
        assertEquals(
            listOf(2_000),
            DubbingChunkTransitionPolicy.markerPositionCandidates(
                chunkSampleCount = 2_000,
                sampleRate = 16_000,
                playbackRate = 1.0f,
                currentHeadPosition = 1_999,
            ),
        )
        assertEquals(
            emptyList<Int>(),
            DubbingChunkTransitionPolicy.markerPositionCandidates(0, 16_000, 1.0f),
        )
    }

}
