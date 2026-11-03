package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerDiarizerChunkingTest {
    private val sampleRate = 16_000L
    private val memoryGuardBytes = 16L * 1024L * 1024L

    @Test
    fun defaultWindowFitsTheFloatBufferSafetyLimit() {
        val chunkSamples = SpeakerDiarizer.DEFAULT_CHUNK_DURATION_MS * sampleRate / 1_000L
        assertTrue("one window must stay under the 16 MB guard", chunkSamples * 4L <= memoryGuardBytes)
    }

    @Test
    fun overlapIsNotClippedByTheOneThirdRule() {
        val chunkSamples = SpeakerDiarizer.DEFAULT_CHUNK_DURATION_MS * sampleRate / 1_000L
        val overlapSamples = SpeakerDiarizer.DEFAULT_OVERLAP_MS * sampleRate / 1_000L
        assertTrue(overlapSamples <= chunkSamples / 3L)
        // Matching people across windows needs enough shared audio; do not shrink it casually.
        assertEquals(20_000L, SpeakerDiarizer.DEFAULT_OVERLAP_MS)
    }

    @Test
    fun defaultWindowIsShorterThanTheOldThreeMinuteWindow() {
        assertTrue(
            SpeakerDiarizer.DEFAULT_CHUNK_DURATION_MS < 3L * 60_000L,
        )
    }
}
