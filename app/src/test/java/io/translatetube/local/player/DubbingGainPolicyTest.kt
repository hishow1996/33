package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingGainPolicyTest {
    @Test
    fun finiteGainIsKeptWithinSupportedRange() {
        assertEquals(0.35f, DubbingGainPolicy.sanitizeGain(0.35f), 0f)
        assertEquals(0f, DubbingGainPolicy.sanitizeGain(-0.2f), 0f)
        assertEquals(1f, DubbingGainPolicy.sanitizeGain(1.4f), 0f)
    }

    @Test
    fun nonFiniteGainFailsClosedToSilence() {
        assertEquals(0f, DubbingGainPolicy.sanitizeGain(Float.NaN), 0f)
        assertEquals(0f, DubbingGainPolicy.sanitizeGain(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, DubbingGainPolicy.sanitizeGain(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun invalidPcmSamplesCannotPropagateToOutput() {
        assertEquals(0f, DubbingGainPolicy.sanitizePcmSample(Float.NaN), 0f)
        assertEquals(0f, DubbingGainPolicy.sanitizePcmSample(Float.POSITIVE_INFINITY), 0f)
        assertEquals(-0.6f, DubbingGainPolicy.sanitizePcmSample(-0.6f), 0f)
    }
}
