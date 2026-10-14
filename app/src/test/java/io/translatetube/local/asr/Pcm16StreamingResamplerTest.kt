package io.translatetube.local.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16StreamingResamplerTest {
    @Test
    fun streamedOutputMatchesWholeArrayResamplingAcrossChunkBoundaries() {
        for (sourceRate in listOf(8_000, 16_000, 22_050, 44_100, 48_000)) {
            val input = ShortArray(997) { index -> ((index * 37) % 65_536 - 32_768).toShort() }
            val expected = referenceResample(input, sourceRate, 16_000)
            val resampler = Pcm16StreamingResampler(sourceRate, 16_000)
            val chunks = mutableListOf<ShortArray>()
            var offset = 0
            while (offset < input.size) {
                val count = minOf((offset % 31) + 1, input.size - offset)
                chunks += resampler.process(input.copyOfRange(offset, offset + count))
                offset += count
            }
            chunks += resampler.finish()
            assertArrayEquals("sourceRate=$sourceRate", expected, chunks.flatMap { it.asIterable() }.toShortArray())
        }
    }

    @Test
    fun emptyAndSingleSampleInputsMatchLegacyLengthSemantics() {
        val emptyResampler = Pcm16StreamingResampler(48_000, 16_000)
        assertArrayEquals(ShortArray(0), emptyResampler.process(ShortArray(0)))
        assertArrayEquals(ShortArray(0), emptyResampler.finish())

        val single = Pcm16StreamingResampler(16_000, 16_000)
        assertArrayEquals(ShortArray(0), single.process(shortArrayOf(1234)))
        assertArrayEquals(shortArrayOf(1234), single.finish())
    }

    @Test
    fun largeStreamingResampleChecksCancellationBetweenOutputChunks() {
        val resampler = Pcm16StreamingResampler(8_000, 16_000)
        var checks = 0
        val error = runCatching {
            resampler.process(ShortArray(100_000) { (it % 100).toShort() }) {
                checks++
                if (checks == 3) throw java.util.concurrent.CancellationException("stale decode")
            }
        }.exceptionOrNull()

        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(3, checks)
    }

    @Test
    fun resamplerFinishChecksCancellationBeforeWritingDeferredTail() {
        val resampler = Pcm16StreamingResampler(16_000, 16_000)
        resampler.process(shortArrayOf(1234))
        var checks = 0
        val error = runCatching {
            resampler.finish {
                checks++
                if (checks == 2) throw java.util.concurrent.CancellationException("stale decode")
            }
        }.exceptionOrNull()

        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(2, checks)
    }

    private fun referenceResample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty() || fromRate == toRate) return input.copyOf()
        val ratio = fromRate.toDouble() / toRate
        val output = ShortArray((input.size / ratio).toInt())
        for (index in output.indices) {
            val position = index * ratio
            val left = position.toInt()
            val right = (left + 1).coerceAtMost(input.size - 1)
            val fraction = position - left
            output[index] = (input[left] + (input[right] - input[left]) * fraction).toInt().toShort()
        }
        return output
    }
}
