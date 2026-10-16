package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingOutputPcmPolicyTest {
    @Test
    fun downSamplesCommonSystemTtsRatesToSixteenKhz() {
        val input = FloatArray(24) { (it / 24f) - 0.5f }
        val normalized = DubbingOutputPcmPolicy.normalize(input, 24_000)
        assertEquals(16_000, normalized.sampleRate)
        assertEquals(16, normalized.samples.size)
        assertTrue(normalized.samples.all { it.isFinite() && it in -1f..1f })
    }

    @Test
    fun keepsAlreadyCompatibleSixteenKhzPcmWithoutCopying() {
        val input = floatArrayOf(-0.5f, 0f, 0.5f)
        val normalized = DubbingOutputPcmPolicy.normalize(input, 16_000)
        assertEquals(16_000, normalized.sampleRate)
        assertSame(input, normalized.samples)
    }

    @Test
    fun keepsEightKhzOutputInsteadOfUpsamplingAndIncreasingMemory() {
        val input = FloatArray(8) { 0.2f }
        val normalized = DubbingOutputPcmPolicy.normalize(input, 8_000)
        assertEquals(8_000, normalized.sampleRate)
        assertSame(input, normalized.samples)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSampleRates() {
        DubbingOutputPcmPolicy.normalize(floatArrayOf(0.2f), 7_999)
    }
    @Test
    fun clipsAndSanitizesCompatibleRatePcmWithoutMutatingTheCachedArray() {
        val input = floatArrayOf(2.0f, -2.0f, Float.NaN, Float.POSITIVE_INFINITY, 0.25f)
        val normalized = DubbingOutputPcmPolicy.normalize(input, 8_000)

        assertEquals(8_000, normalized.sampleRate)
        assertTrue(normalized.samples.contentEquals(floatArrayOf(1.0f, -1.0f, 0.0f, 0.0f, 0.25f)))
        assertTrue(input[0] == 2.0f && input[1] == -2.0f)
    }

    @Test
    fun keepsZeroCopyFastPathForAlreadySafeLowRatePcm() {
        val input = floatArrayOf(-1.0f, -0.2f, 0.0f, 0.6f, 1.0f)
        assertSame(input, DubbingOutputPcmPolicy.normalize(input, 8_000).samples)
    }

    @Test
    fun keepsTinyNonEmptyHighRatePcmAsOneTargetSample() {
        val input = floatArrayOf(0.4f)
        val normalized = DubbingOutputPcmPolicy.normalize(input, 192_000)

        assertEquals(16_000, normalized.sampleRate)
        assertEquals(1, normalized.samples.size)
        assertEquals(0.4f, normalized.samples.single(), 0.0001f)
    }

}