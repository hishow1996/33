package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTtsOutputAudioPolicyTest {
    @Test
    fun normalizesSystemWavDirectlyToThePlaybackRate() {
        // Using a 48 kHz source makes every second source sample an exact 24 kHz output sample.
        val input = TtsAudio(
            floatArrayOf(0f, 0f, 0.5f, 0f, 0f, 0f, -0.5f, 0f, 0f, 0f, 0.25f, 0f),
            48_000,
        )

        val result = SystemTtsOutputAudioPolicy.normalize(input)

        assertEquals(24_000, result.sampleRate)
        assertEquals(6, result.samples.size)
        assertEquals(0f, result.samples[0], 0f)
        assertEquals(0.5f, result.samples[1], 0.001f)
        assertEquals(0f, result.samples[2], 0f)
        assertEquals(-0.5f, result.samples[3], 0.001f)
        assertEquals(0f, result.samples[4], 0f)
        assertEquals(0.25f, result.samples[5], 0.001f)
        assertTrue(result.samples.all { it.isFinite() && it in -1f..1f })
    }

    @Test
    fun leavesAlreadyNormalizedAndEmptyAudioUnchanged() {
        val normalized = TtsAudio(floatArrayOf(0f, 0.25f), 24_000)
        assertSame(normalized, SystemTtsOutputAudioPolicy.normalize(normalized))

        val empty = TtsAudio(FloatArray(0), 24_000)
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

    @Test
    fun trimsLeadingAndTrailingSilenceAfterRateNormalization() {
        // 48 kHz input normalizes to 7,500 samples at 24 kHz before trimming.
        val sourceSamples = FloatArray(15_000).apply {
            for (index in 6_000 until 9_000) this[index] = 0.25f
        }
        val result = SystemTtsOutputAudioPolicy.prepareForPlayback(TtsAudio(sourceSamples, 48_000))

        // 25 ms of padding on each side remains; long leading/trailing silence is removed.
        assertEquals(24_000, result.sampleRate)
        assertEquals(2_700, result.samples.size)
        assertEquals(0f, result.samples[0], 0f)
        assertEquals(0.25f, result.samples[600], 0f)
        assertEquals(0f, result.samples.last(), 0f)
    }

    @Test
    fun preservesEntirelySilentSystemTtsAudio() {
        val silent = TtsAudio(FloatArray(2_000), 24_000)
        assertSame(silent, SystemTtsOutputAudioPolicy.prepareForPlayback(silent))
    }


@Test
    fun sameRateNonFiniteOrOutOfRangePcmIsCopiedAndSanitized() {
        val source = TtsAudio(floatArrayOf(2f, -2f, Float.NaN, Float.POSITIVE_INFINITY, 0.5f), 24_000)
        val normalized = SystemTtsOutputAudioPolicy.normalize(source)
        assertTrue(normalized !== source)
        assertEquals(24_000, normalized.sampleRate)
        assertTrue(normalized.samples.contentEquals(floatArrayOf(1f, -1f, 0f, 0f, 0.5f)))
        assertEquals(2f, source.samples[0], 0f)
    }

    @Test
    fun alreadySafeSameRateAudioKeepsTheZeroCopyFastPath() {
        val source = TtsAudio(floatArrayOf(-1f, 0f, 0.7f), 24_000)
        assertSame(source, SystemTtsOutputAudioPolicy.normalize(source))
    }

    @Test
    fun sameRateSanitizationCanBeCancelledBeforePublishingAudio() {
        val source = TtsAudio(FloatArray(30_000) { 2f }, 24_000)
        var checks = 0
        val error = runCatching {
            SystemTtsOutputAudioPolicy.normalize(source) {
                checks++
                if (checks == 2) throw java.util.concurrent.CancellationException("stale conversion")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }

    @Test
    fun resamplingCanBeCancelledBeforeReturningAudio() {
        val source = TtsAudio(FloatArray(30_000) { 0.25f }, 48_000)
        var checks = 0
        val error = runCatching {
            SystemTtsOutputAudioPolicy.normalize(source) {
                checks++
                if (checks == 2) throw java.util.concurrent.CancellationException("stale resample")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }

}
