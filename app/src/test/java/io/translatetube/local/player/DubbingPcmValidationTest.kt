package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPcmValidationTest {
    @Test
    fun acceptsFiniteAudiblePcmAtSupportedRates() {
        assertTrue(DubbingPcmValidation.isValidAudio(16_000, floatArrayOf(0f, 0.03f, -0.2f)))
        assertTrue(DubbingPcmValidation.isValidAudio(48_000, floatArrayOf(0f, -0.00002f)))
    }

    @Test
    fun rejectsEmptySilentOrNearlySilentPcm() {
        assertFalse(DubbingPcmValidation.isValidAudio(16_000, floatArrayOf()))
        assertFalse(DubbingPcmValidation.isValidAudio(16_000, FloatArray(128)))
        assertFalse(DubbingPcmValidation.isValidAudio(16_000, floatArrayOf(0f, 0.000001f)))
    }

    @Test
    fun rejectsUnsupportedSampleRatesAndNonFiniteSamples() {
        assertFalse(DubbingPcmValidation.isValidAudio(7_999, floatArrayOf(0.1f)))
        assertFalse(DubbingPcmValidation.isValidAudio(192_001, floatArrayOf(0.1f)))
        assertFalse(DubbingPcmValidation.isValidAudio(16_000, floatArrayOf(0.1f, Float.NaN)))
        assertFalse(DubbingPcmValidation.isValidAudio(16_000, floatArrayOf(Float.POSITIVE_INFINITY)))
    }

    @Test
    fun rejectsUnboundedPcmBeforeScanningEverySample() {
        assertTrue(DubbingPcmValidation.MAX_SAMPLE_COUNT == 8_000_000)
        assertFalse(DubbingPcmValidation.isSampleCountAllowed(0))
        assertTrue(DubbingPcmValidation.isSampleCountAllowed(1))
        assertTrue(DubbingPcmValidation.isSampleCountAllowed(DubbingPcmValidation.MAX_SAMPLE_COUNT))
        assertFalse(DubbingPcmValidation.isSampleCountAllowed(DubbingPcmValidation.MAX_SAMPLE_COUNT + 1))
    }

    @Test
    fun cacheHeaderValidationAcceptsValidAudioAndEmptyMarkers() {
        assertTrue(DubbingPcmValidation.isValidCacheHeader(24_000, 24_000, 8L + 24_000L * 4L))
        assertTrue(DubbingPcmValidation.isValidCacheHeader(16_000, 0, 8L))
    }

    @Test
    fun cacheHeaderValidationRejectsInvalidRateCountAndLength() {
        assertFalse(DubbingPcmValidation.isValidCacheHeader(7_999, 1, 12L))
        assertFalse(DubbingPcmValidation.isValidCacheHeader(192_001, 1, 12L))
        assertFalse(DubbingPcmValidation.isValidCacheHeader(16_000, -1, 4L))
        assertFalse(DubbingPcmValidation.isValidCacheHeader(
            16_000,
            DubbingPcmValidation.MAX_SAMPLE_COUNT + 1,
            8L + (DubbingPcmValidation.MAX_SAMPLE_COUNT.toLong() + 1L) * 4L,
        ))
        assertFalse(DubbingPcmValidation.isValidCacheHeader(16_000, 10, 8L + 9L * 4L))
    }

    @Test
    fun largePcmValidationCanBeCancelledBetweenChunks() {
        val samples = FloatArray(20_000) { 0.01f }
        var checks = 0
        val error = runCatching {
            DubbingPcmValidation.isValidAudio(16_000, samples) {
                checks++
                if (checks == 2) throw java.util.concurrent.CancellationException("cancelled")
            }
        }.exceptionOrNull()

        assertTrue(error is java.util.concurrent.CancellationException)
        assertTrue(checks >= 2)
    }

    @Test
    fun audibleSamplePredicateRejectsNonFiniteAndLowAmplitudeValues() {
        assertTrue(DubbingPcmValidation.isAudibleSample(-0.001f))
        assertFalse(DubbingPcmValidation.isAudibleSample(0.000001f))
        assertFalse(DubbingPcmValidation.isAudibleSample(Float.NaN))
    }

@Test
    fun cancellablePrefixCopyPreservesExactSamples() {
        val source = FloatArray(40_000) { index -> index / 40_000f }
        val copied = DubbingPcmValidation.copyPrefix(source, 20_001)
        assertEquals(20_001, copied.size)
        assertEquals(source[0], copied[0], 0f)
        assertEquals(source[20_000], copied[20_000], 0f)
    }

    @Test
    fun prefixCopyRejectsNegativeOversizedAndOutOfSourceRanges() {
        assertTrue(runCatching { DubbingPcmValidation.copyPrefix(floatArrayOf(1f), -1) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { DubbingPcmValidation.copyPrefix(floatArrayOf(1f), 2) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            DubbingPcmValidation.copyPrefix(floatArrayOf(1f), DubbingPcmValidation.MAX_SAMPLE_COUNT + 1)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun prefixCopyRespondsToCancellationDuringCopy() {
        val source = FloatArray(50_000) { 0.5f }
        var checks = 0
        val error = runCatching {
            DubbingPcmValidation.copyPrefix(source, source.size) {
                checks++
                if (checks == 2) throw java.util.concurrent.CancellationException("copy obsolete PCM")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(2, checks)
    }

}
