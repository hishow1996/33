package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16SampleConverterTest {
    @Test
    fun convertsFloatPcmEndpointsAndSilence() {
        assertEquals(Short.MIN_VALUE, Pcm16SampleConverter.floatToPcm16(-1.0f))
        assertEquals(Short.MAX_VALUE, Pcm16SampleConverter.floatToPcm16(1.0f))
        assertEquals(0.toShort(), Pcm16SampleConverter.floatToPcm16(0.0f))
    }

    @Test
    fun clampsFloatPcmThatExceedsNominalRange() {
        assertEquals(Short.MIN_VALUE, Pcm16SampleConverter.floatToPcm16(-1.5f))
        assertEquals(Short.MAX_VALUE, Pcm16SampleConverter.floatToPcm16(1.5f))
    }

    @Test
    fun rejectsNonFiniteFloatSamples() {
        assertEquals(false, runCatching { Pcm16SampleConverter.floatToPcm16(Float.NaN) }.isSuccess)
        assertEquals(false, runCatching { Pcm16SampleConverter.floatToPcm16(Float.POSITIVE_INFINITY) }.isSuccess)
    }
}
