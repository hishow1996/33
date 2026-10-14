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
        val pcm = FloatArray(128_000) { index -> ((index % 113) - 56) / 56f }
        val signature = AsrTranscriptCheckpointPolicy.signature(
            pcm,
            listOf(0L to 1_000L, 2_000L to 3_000L),
        )
        assertTrue(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 1))
        assertTrue(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 2))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, "other", 2, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 3, 2, 1))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume(signature, signature, 2, 2, 3))
        assertFalse(AsrTranscriptCheckpointPolicy.canResume("", signature, 2, 2, 1))
    }
    @Test
    fun signatureIncludesFinalPcmSampleWhenStrideDoesNotReachIt() {
        val pcm = FloatArray(4_098) { index -> (index % 37) / 37f }
        val segments = listOf(0L to 900L, 1_200L to 2_400L)
        val original = AsrTranscriptCheckpointPolicy.signature(pcm, segments)
        val changedTail = pcm.copyOf().also { it[it.lastIndex] = -0.875f }

        assertFalse(original == AsrTranscriptCheckpointPolicy.signature(changedTail, segments))
    }

    @Test
    fun signatureChangesWhenOnlyANonAnchorPcmSampleChanges() {
        val pcm = FloatArray(8_194) { index -> ((index % 41) - 20) / 41f }
        val segments = listOf(0L to 1_200L, 1_600L to 2_900L)
        val original = AsrTranscriptCheckpointPolicy.signature(pcm, segments)
        val changed = pcm.copyOf().also { it[4_103] = -0.9375f }

        assertFalse(original == AsrTranscriptCheckpointPolicy.signature(changed, segments))
    }

}
