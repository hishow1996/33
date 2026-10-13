package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpeechRatePolicyTest {
    @Test
    fun normalizesFiniteRatesToSupportedRange() {
        assertEquals(0.5f, TtsSpeechRatePolicy.normalize(0.1f), 0.001f)
        assertEquals(1.5f, TtsSpeechRatePolicy.normalize(1.5f), 0.001f)
        assertEquals(2.0f, TtsSpeechRatePolicy.normalize(4.0f), 0.001f)
    }

    @Test
    fun nonFiniteRatesUseNormalSpeed() {
        assertEquals(1.0f, TtsSpeechRatePolicy.normalize(Float.NaN), 0.001f)
        assertEquals(1.0f, TtsSpeechRatePolicy.normalize(Float.POSITIVE_INFINITY), 0.001f)
        assertEquals(1.0f, TtsSpeechRatePolicy.normalize(Float.NEGATIVE_INFINITY), 0.001f)
    }
}
