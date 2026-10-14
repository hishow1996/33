package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmDecodeLimitPolicyTest {
    @Test
    fun recomputesThreeMinuteLimitFromActualDecoderFormat() {
        assertEquals(
            17_280_000L,
            PcmDecodeLimitPolicy.maxSourceSamples(48_000, 2, 180_000L),
        )
        assertEquals(
            7_938_000L,
            PcmDecodeLimitPolicy.maxSourceSamples(44_100, 1, 180_000L),
        )
        assertEquals(
            15_876_000L,
            PcmDecodeLimitPolicy.maxSourceSamples(44_100, 2, 180_000L),
        )
    }

    @Test
    fun durationLimitAlwaysEndsOnACompleteChannelFrame() {
        val samples = PcmDecodeLimitPolicy.maxSourceSamples(48_000, 6, 1L)
        assertEquals(6L, samples)
        assertEquals(0L, samples % 6L)
    }

    @Test
    fun unlimitedDurationRetainsARepresentableBound() {
        val samples = PcmDecodeLimitPolicy.maxSourceSamples(48_000, 2, Long.MAX_VALUE)
        assertEquals(2_147_483_646L, samples)
        assertTrue(samples > 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChannelCount() {
        PcmDecodeLimitPolicy.maxSourceSamples(48_000, 0, 180_000L)
    }
}
