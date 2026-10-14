package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrTranscriptCheckpointPolicyTest {
    @Test
    fun signatureIsStableForSamePcmLengthAndVadSegments() {
        val segments = listOf(0L to 900L, 1_200L to 2_400L, 3_100L to 4_000L)
        assertEquals(
            AsrTranscriptCheckpointPolicy.signature(64_000, segments),
            AsrTranscriptCheckpointPolicy.signature(64_000, segments.toList()),
        )
    }

    @Test
    fun signatureChangesWhenAudioLengthOrVadLayoutChanges() {
        val segments = listOf(0L to 900L, 1_200L to 2_400L)
        val signature = AsrTranscriptCheckpointPolicy.signature(64_000, segments)
        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(64_001, segments))
        assertFalse(signature == AsrTranscriptCheckpointPolicy.signature(64_000, listOf(0L to 800L, 1_200L to 2_400L)))
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
