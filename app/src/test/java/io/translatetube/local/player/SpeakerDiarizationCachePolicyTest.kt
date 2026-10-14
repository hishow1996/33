package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun speakerTimelineRoundTripsWithoutLosingTimelineOrSpeakerIds() {
        val signature = "audio-fingerprint"
        val expected = listOf(
            SpeakerDiarizationRecord(0.125f, 1.250f, 0, 0.91f),
            SpeakerDiarizationRecord(1.500f, 2.875f, 2, 0.77f),
        )
        val encoded = SpeakerDiarizationCachePolicy.encodeSegments(signature, expected)

        assertEquals(expected, SpeakerDiarizationCachePolicy.decodeSegments(encoded, signature, signature))
    }

    @Test
    fun rejectsMismatchedFingerprintMalformedRowsAndInvalidIntervals() {
        val expectedSignature = "expected"
        val otherSignature = "other"
        val validOther = SpeakerDiarizationCachePolicy.encodeSegments(
            otherSignature,
            listOf(SpeakerDiarizationRecord(0f, 1f, 0, 0.9f)),
        )
        val invalidInterval = "speaker-diarization-v1|expected\n1065353216,1065353216,0,1063675494"

        assertNull(SpeakerDiarizationCachePolicy.decodeSegments(validOther, otherSignature, expectedSignature))
        assertNull(SpeakerDiarizationCachePolicy.decodeSegments("bad", expectedSignature, expectedSignature))
        assertNull(SpeakerDiarizationCachePolicy.decodeSegments(invalidInterval, expectedSignature, expectedSignature))
    }
    @Test
    fun cacheEvictionKeepsTheNewestFiveAndNeverEvictsTheCurrentKey() {
        val newestFirst = listOf(
            "v6|speaker-diarization-v1", "v5|speaker-diarization-v1",
            "v4|speaker-diarization-v1", "v3|speaker-diarization-v1",
            "v2|speaker-diarization-v1", "v1|speaker-diarization-v1",
        )
        assertEquals(
            listOf("v1|speaker-diarization-v1"),
            SpeakerDiarizationCachePolicy.keysToEvict(newestFirst, "v6|speaker-diarization-v1"),
        )
    }

    @Test
    fun cacheEvictionPreservesCurrentKeyEvenIfTheQueryOrderIsUnexpected() {
        val keys = listOf("old-a", "old-b", "current", "old-c", "old-d", "old-e")
        assertFalse(SpeakerDiarizationCachePolicy.keysToEvict(keys, "current").contains("current"))
        assertEquals(1, SpeakerDiarizationCachePolicy.keysToEvict(keys, "current").size)
    }

    @Test
    fun signatureChangesWhenOnlyANonAnchorSampleChanges() {
        val samples = FloatArray(8_194) { index -> (index % 43) / 43f }
        val changed = samples.copyOf().also { it[4_103] = -0.9375f }

        assertFalse(
            SpeakerDiarizationCachePolicy.signature(samples) ==
                SpeakerDiarizationCachePolicy.signature(changed)
        )
    }

}
