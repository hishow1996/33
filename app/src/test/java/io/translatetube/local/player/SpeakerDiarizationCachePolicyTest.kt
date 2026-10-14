package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun rejectsSavedResultsFromAnotherAudioFingerprint() {
        val expected = SpeakerDiarizationCachePolicy.signature(floatArrayOf(0.1f, 0.2f))
        assertTrue(
            SpeakerDiarizationCachePolicy.decodeSegments(
                """{"version":1,"signature":"other","segments":[{"s":0,"e":1,"sp":0,"c":0.8}]}""",
                "other",
                expected,
            ) == null
        )
    }
}
