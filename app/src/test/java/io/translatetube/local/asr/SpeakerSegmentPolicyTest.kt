package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerSegmentPolicyTest {
    @Test
    fun clipsSegmentEndToMediaDuration() {
        val segment = SpeakerSegmentPolicy.normalize(1f, 20f, 0, 0.8f, 10f)

        assertEquals(1f, segment!!.startSec, 0f)
        assertEquals(10f, segment!!.endSec, 0f)
    }

    @Test
    fun rejectsNegativeOrOutOfRangeSegmentStarts() {
        assertNull(SpeakerSegmentPolicy.normalize(-0.1f, 1f, 0, 0.8f, 10f))
        assertNull(SpeakerSegmentPolicy.normalize(10f, 11f, 0, 0.8f, 10f))
    }

    @Test
    fun rejectsNonFiniteAndReversedTimestamps() {
        assertNull(SpeakerSegmentPolicy.normalize(Float.NaN, 1f, 0, 0.8f, 10f))
        assertNull(SpeakerSegmentPolicy.normalize(1f, Float.POSITIVE_INFINITY, 0, 0.8f, 10f))
        assertNull(SpeakerSegmentPolicy.normalize(2f, 1f, 0, 0.8f, 10f))
        assertNull(SpeakerSegmentPolicy.normalize(1f, 1f, 0, 0.8f, 10f))
    }

    @Test
    fun rejectsInvalidSpeakerIdsAndDuration() {
        assertNull(SpeakerSegmentPolicy.normalize(0f, 1f, -1, 0.8f, 10f))
        assertNull(SpeakerSegmentPolicy.normalize(0f, 1f, 0, 0.8f, 0f))
        assertNull(SpeakerSegmentPolicy.normalize(0f, 1f, 0, 0.8f, Float.NaN))
    }

    @Test
    fun clampsConfidenceAndUsesUnknownSentinelForInvalidValues() {
        assertEquals(1f, SpeakerSegmentPolicy.normalize(0f, 1f, 0, 2f, 10f)!!.confidence, 0f)
        assertEquals(0f, SpeakerSegmentPolicy.normalize(0f, 1f, 0, 0f, 10f)!!.confidence, 0f)
        assertEquals(
            SpeakerSegmentPolicy.UNKNOWN_CONFIDENCE,
            SpeakerSegmentPolicy.normalize(0f, 1f, 0, Float.NaN, 10f)?.confidence,
        )
    }
}
