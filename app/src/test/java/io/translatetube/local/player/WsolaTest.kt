package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class WsolaTest {
    @Test
    fun stretchHandlesReportedTailLengthsAcrossCompressionAndExpansionRatios() {
        val lengths = intArrayOf(44_928, 48_960, 71_424, 132_480)
        val ratios = floatArrayOf(0.67f, 0.8f, 0.97f, 1.2f, 1.5f)

        for (length in lengths) {
            val samples = FloatArray(length) { index ->
                sin(index * 0.017).toFloat() * 0.7f
            }
            for (ratio in ratios) {
                val output = Wsola.stretch(samples, ratio)
                assertEquals(
                    "unexpected output length for input=$length ratio=$ratio",
                    (length.toDouble() * ratio).toInt().coerceAtLeast(1),
                    output.size,
                )
                assertTrue(
                    "output should retain audio for input=$length ratio=$ratio",
                    output.any { it != 0f },
                )
                assertTrue(
                    "tail should not be silently zero-padded for input=$length ratio=$ratio",
                    output.takeLast(minOf(128, output.size)).any { kotlin.math.abs(it) > 0.000001f },
                )
                assertFalse(
                    "output must not contain NaN for input=$length ratio=$ratio",
                    output.any { it.isNaN() },
                )
            }
        }
    }

    @Test
    fun stretchUsesSafeInterpolationForCuesShorterThanOneWindow() {
        val sizes = intArrayOf(1, 17, 511, 1_023, 1_024)
        val ratios = floatArrayOf(0.5f, 0.8f, 1.2f, 1.5f)

        for (size in sizes) {
            val samples = FloatArray(size) { index -> (index + 1).toFloat() / size }
            for (ratio in ratios) {
                val output = Wsola.stretch(samples, ratio)
                assertEquals(
                    "unexpected short-cue output length for input=$size ratio=$ratio",
                    (size.toDouble() * ratio).toInt().coerceAtLeast(1),
                    output.size,
                )
                assertTrue(output.all { it.isFinite() })
                assertTrue("short cue should not become entirely silent", output.any { it > 0f })
            }
        }
    }

    @Test
    fun ratioOneReturnsOriginalSamplesWithoutAllocatingAnotherBuffer() {
        val samples = FloatArray(2_048) { it.toFloat() }
        assertTrue(samples === Wsola.stretch(samples, 1.0f))
    }
}
