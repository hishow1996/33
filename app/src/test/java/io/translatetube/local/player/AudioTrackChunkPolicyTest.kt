package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackChunkPolicyTest {
    @Test
    fun largeStaticBuffersAreRetriedWithSmallerChunks() {
        assertEquals(
            listOf(24_000, 12_000, 6_000, 3_000, 2_048),
            AudioTrackChunkPolicy.candidateSampleCounts(24_000),
        )
    }

    @Test
    fun shortCuesAreNotPaddedOrSplitUnnecessarily() {
        assertEquals(listOf(1_200), AudioTrackChunkPolicy.candidateSampleCounts(1_200))
        assertEquals(listOf(4_000), AudioTrackChunkPolicy.candidateSampleCounts(4_000, 4_000))
    }

    @Test
    fun invalidSizesReturnNoAttempts() {
        assertTrue(AudioTrackChunkPolicy.candidateSampleCounts(0).isEmpty())
        assertTrue(AudioTrackChunkPolicy.candidateSampleCounts(-5).isEmpty())
    }

    @Test
    fun errorExplainsBufferRetriesAndIncludesBoundedLastFailure() {
        val message = AudioTrackChunkPolicy.failureMessage(
            24_000,
            listOf(24_000, 12_000, 6_000, 3_000, 2_048),
            "legacy initialization returned false",
        )
        assertTrue(message.contains("24,000, 12,000, 6,000, 3,000, 2,048"))
        assertTrue(message.contains("24000Hz"))
        assertTrue(message.contains("legacy initialization"))
    }
}
