package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingPcm16ConverterTest {
    @Test
    fun convertsNormalizedSamplesAndClampsTheSignedEndpoints() {
        val converted = DubbingPcm16Converter.convert(
            floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f),
        )
        assertEquals(Short.MIN_VALUE, converted[0])
        assertEquals(-16384, converted[1].toInt())
        assertEquals(0, converted[2].toInt())
        assertEquals(16384, converted[3].toInt())
        assertEquals(Short.MAX_VALUE, converted[4])
    }

    @Test
    fun replacesNonFiniteSamplesWithSilence() {
        val converted = DubbingPcm16Converter.convert(
            floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
        )
        assertEquals(0, converted[0].toInt())
        assertEquals(0, converted[1].toInt())
        assertEquals(0, converted[2].toInt())
    }

    @Test
    fun preservesSampleCount() {
        assertEquals(
            4,
            DubbingPcm16Converter.convert(floatArrayOf(-0.8f, -0.1f, 0.1f, 0.8f)).size,
        )
    }
}
