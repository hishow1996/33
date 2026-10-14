package io.translatetube.local.player

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
        assertFalse(DubbingPcmValidation.isSampleCountAllowed(0))
        assertTrue(DubbingPcmValidation.isSampleCountAllowed(1))
        assertTrue(DubbingPcmValidation.isSampleCountAllowed(DubbingPcmValidation.MAX_SAMPLE_COUNT))
        assertFalse(DubbingPcmValidation.isSampleCountAllowed(DubbingPcmValidation.MAX_SAMPLE_COUNT + 1))
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
}
