package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeakerDiarizationCachePolicyTest {
    @Test
    fun signatureIsStableForTheSameAudioSamples() {
        val samples = FloatArray(64_000) { index -> ((index % 97) - 48) / 48f }
        assertEquals(
            SpeakerDiarizationCachePolicy.signature(samples),
            SpeakerDiarizationCachePolicy.signature(samples.copyOf()),
        )
    }

    @Test
    fun signatureChangesWhenLengthOrAnchoredAudioContentChanges() {
        val samples = FloatArray(4_098) { index -> (index % 37) / 37f }
        val signature = SpeakerDiarizationCachePolicy.signature(samples)
        val changedTail = samples.copyOf().also { it[it.lastIndex] = -0.875f }
        val changedLength = samples.copyOf(samples.size + 1)

        assertFalse(signature == SpeakerDiarizationCachePolicy.signature(changedTail))
        assertFalse(signature == SpeakerDiarizationCachePolicy.signature(changedLength))
    }

}
