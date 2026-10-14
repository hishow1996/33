package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrTranscriptCheckpointPolicyTest {
    @Test
    fun signatureIsStableForSamePcmAndVadSegments() {
        val segments = listOf(0L to 900L, 1_200L to 2_400L, 3_100L to 4_000L)
        val pcm = FloatArray(64_000) { index -> ((index % 97) - 48) / 48f }
        assertEquals(
            AsrTranscriptCheckpointPolicy.signature(pcm, segments),
            AsrTranscriptCheckpointPolicy.signature(pcm, segments.toList()),
        )
    }

    @Test
    fun signatureChangesWhenAudioLengthPcmContentOrVadLayoutChanges() {
        val pcm = FloatArray(4_096) { index -> (index % 31) / 31f }
        val segments = listOf(0L to 900L, 1_200L to 2_400L)
        val signature = AsrTranscriptCheckpointPolicy.signature(pcm, segments)

        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(FloatArray(4_097), segments))
        val changedPcm = pcm.copyOf().also { it[2_048] = -0.75f }
        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(changedPcm, segments))
        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(pcm, listOf(0L to 800L, 1_200L to 2_400L)))
    }

    @Test
    fun onlyResumesMatchingCheckpointWithValidProcessedCount() {
        val signature = AsrTranscriptCheckpointPolicy.signature(128_000, listOf(0L to 1_000L, 2_000L to 3_000L))
        assertTrue(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 1))
        assertTrue(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 2))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, "other", 2, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 3, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 3))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume("", signature, 2, 2, 1))
    }
}
