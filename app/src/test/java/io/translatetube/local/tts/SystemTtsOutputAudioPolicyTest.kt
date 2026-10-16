package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTtsOutputAudioPolicyTest {
    @Test
    fun normalizesSystemWavDirectlyToThePlaybackRate() {
        // Using a 48 kHz source makes every third source sample an exact 16 kHz output sample.
        val input = TtsAudio(
            floatArrayOf(0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0f, -0.5f, 0f, 0f),
            48_000,
        )

        val result = SystemTtsOutputAudioPolicy.normalize(input)

        assertEquals(16_000, result.sampleRate)
        assertEquals(4, result.samples.size)
        assertEquals(0f, result.samples[0], 0f)
        assertEquals(0.5f, result.samples[1], 0.001f)
        assertEquals(0f, result.samples[2], 0f)
        assertEquals(-0.5f, result.samples[3], 0.001f)
        assertTrue(result.samples.all { it.isFinite() && it in -1f..1f })
    }

    @Test
    fun leavesAlreadyNormalizedAndEmptyAudioUnchanged() {
        val normalized = TtsAudio(floatArrayOf(0f, 0.25f), 16_000)
        assertSame(normalized, SystemTtsOutputAudioPolicy.normalize(normalized))

        val empty = TtsAudio(FloatArray(0), 16_000)
        assertSame(empty, SystemTtsOutputAudioPolicy.normalize(empty))
    }

    @Test
    fun durationStaysApproximatelyConstantAfterRateConversion() {
        val input = TtsAudio(FloatArray(3_200) { index -> if (index % 20 < 10) 0.3f else -0.3f }, 32_000)
        val result = SystemTtsOutputAudioPolicy.normalize(input)

        val inputDuration = input.samples.size.toDouble() / input.sampleRate
        val outputDuration = result.samples.size.toDouble() / result.sampleRate
        assertEquals(inputDuration, outputDuration, 1.0 / result.sampleRate)
    }

    @Test
    fun excessiveResamplingAllocationIsSkippedSafely() {
        assertEquals(
            null,
            SystemTtsOutputAudioPolicy.targetSampleCount(
                inputSampleCount = 2_100_000,
                inputSampleRate = 8_000,
            ),
        )
        val audio = TtsAudio(FloatArray(2_100_000), 8_000)
        assertSame(audio, SystemTtsOutputAudioPolicy.normalize(audio))
    }

    @Test
    fun invalidRatesAreNotUsedToCalculateAnAllocation() {
        assertEquals(null, SystemTtsOutputAudioPolicy.targetSampleCount(100, 7_999))
        assertEquals(null, SystemTtsOutputAudioPolicy.targetSampleCount(100, 24_000, 0))
    }
}
