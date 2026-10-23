package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** 语音类的谐波信号：基频 180 Hz，恒定幅度，便于测电平与音高。 */
    private fun steadyVoice(sampleRate: Int, seconds: Int): FloatArray =
        FloatArray(sampleRate * seconds) { i ->
            val t = i.toDouble() / sampleRate
            (0.25 * sin(2 * PI * 180 * t) + 0.15 * sin(2 * PI * 360 * t) + 0.1 * sin(2 * PI * 540 * t)).toFloat()
        }

    private fun windowRms(x: FloatArray, window: Int, from: Int, to: Int): List<Double> {
        val result = ArrayList<Double>()
        var i = from
        while (i + window <= to) {
            var sum = 0.0
            for (k in 0 until window) sum += x[i + k].toDouble() * x[i + k]
            result += sqrt(sum / window)
            i += window
        }
        return result
    }

    @Test
    fun overlapAddKeepsLevelFlatAcrossRatios() {
        val sampleRate = 24_000
        val source = steadyVoice(sampleRate, 3)
        val sourceRms = windowRms(source, 1024, 4096, source.size - 4096).average()
        for (ratio in floatArrayOf(0.6f, 0.667f, 0.8f, 0.9f, 1.2f, 1.5f)) {
            val output = Wsola.stretch(source, ratio)
            val rms = windowRms(output, 1024, 4096, output.size - 4096)
            val dipDb = 20 * log10(rms.min() / rms.max())
            assertTrue("level ripple should stay under 0.5 dB, ratio=$ratio dip=$dipDb", dipDb > -0.5)
            val levelDb = 20 * log10(rms.average() / sourceRms)
            assertTrue("level should match the source within 0.5 dB, ratio=$ratio diff=$levelDb", abs(levelDb) < 0.5)
        }
    }

    @Test
    fun stretchPreservesPitchWhenChangingDuration() {
        val sampleRate = 24_000
        val source = steadyVoice(sampleRate, 3)
        for (ratio in floatArrayOf(0.667f, 0.8f, 1.25f)) {
            val output = Wsola.stretch(source, ratio)
            // 取输出中段估计基频周期（自相关峰）；应仍约为 24000/180≈133 个采样点。
            val start = output.size / 2
            val length = 4096
            var bestLag = 0
            var bestScore = -1.0
            for (lag in 100..180) {
                var score = 0.0
                for (i in 0 until length) score += output[start + i].toDouble() * output[start + i + lag]
                if (score > bestScore) { bestScore = score; bestLag = lag }
            }
            assertTrue("pitch period should stay ≈133 samples, ratio=$ratio lag=$bestLag", abs(bestLag - 133) <= 3)
        }
    }

    @Test
    fun stretchFollowsTheTimeScaledEnvelopeOfSpeechLikeBursts() {
        // 三个"音节"（带包络的有声段）+ 静音；压缩后音节仍应存在，且位置按比例前移。
        val sampleRate = 24_000
        val total = sampleRate * 3
        val source = FloatArray(total)
        val bursts = intArrayOf(sampleRate / 2, sampleRate * 3 / 2, sampleRate * 5 / 2)
        for (center in bursts) {
            for (i in -sampleRate / 5..sampleRate / 5) {
                val envelope = 0.5 * (1 + kotlin.math.cos(PI * i / (sampleRate / 5.0)))
                source[center + i] = (0.6 * envelope * sin(2 * PI * 200 * (center + i) / sampleRate)).toFloat()
            }
        }
        val ratio = 0.7f
        val output = Wsola.stretch(source, ratio)
        val frame = 480
        val energies = windowRms(output, frame, 0, output.size)
        for (center in bursts) {
            val expected = (center * ratio).toInt() / frame
            val near = energies.subList((expected - 2).coerceAtLeast(0), (expected + 3).coerceAtMost(energies.size))
            assertTrue(
                "burst at $center should remain audible near ${expected * frame} after compression",
                near.max() > 0.15,
            )
        }
        // 两个音节之间的静音处不应出现明显的能量（没有把相邻音节糊在一起）。
        val gap = ((bursts[0] + bursts[1]) / 2 * ratio).toInt() / frame
        assertTrue("silence between bursts must stay quiet", energies[gap] < 0.05)
    }

    @Test
    fun compressedSpeechHasNoClicksAtFrameJoins() {
        val sampleRate = 24_000
        val source = steadyVoice(sampleRate, 3)
        val output = Wsola.stretch(source, 0.667f)
        var maxStep = 0.0
        for (i in 1 until output.size) maxStep = maxOf(maxStep, abs((output[i] - output[i - 1]).toDouble()))
        var sourceStep = 0.0
        for (i in 1 until source.size) sourceStep = maxOf(sourceStep, abs((source[i] - source[i - 1]).toDouble()))
        assertTrue(
            "sample-to-sample jumps must stay close to the source's own slope: $maxStep vs $sourceStep",
            maxStep < sourceStep * 1.6,
        )
    }

    @Test
    fun outputIsExactlyTheRequestedLengthAndKeepsBothEnds() {
        val sampleRate = 24_000
        val source = steadyVoice(sampleRate, 2)
        val output = Wsola.stretch(source, 0.8f)
        assertEquals((source.size.toDouble() * 0.8f).toInt(), output.size)
        // 开头不应被淡入吞掉，结尾也不应被淡出吞掉。
        assertTrue(output.take(256).maxOf { abs(it) } > 0.2f)
        assertTrue(output.takeLast(256).maxOf { abs(it) } > 0.2f)
    }
}
