package io.translatetube.local.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCenterSplitterTest {

    private val rate = 48_000
    private val oneSecond = rate

    private fun sine(freq: Double, index: Int, amplitude: Float = 0.5f): Float =
        (amplitude * sin(2.0 * PI * freq * index / rate)).toFloat()

    private class Rms {
        private var sum = 0.0
        private var n = 0
        fun add(value: Float) { sum += value.toDouble() * value; n++ }
        val value: Double get() = if (n == 0) 0.0 else sqrt(sum / n)
    }

    /** 跑 [seconds] 秒，前半秒让滤波器稳定，之后统计各路输出的 RMS。 */
    private fun run(
        seconds: Int = 2,
        left: (Int) -> Float,
        right: (Int) -> Float,
    ): Map<String, Double> {
        val splitter = SpeechCenterSplitter(rate)
        val inLeft = Rms(); val bedLeft = Rms(); val bedRight = Rms(); val center = Rms()
        val settle = rate / 2
        for (i in 0 until seconds * oneSecond) {
            val l = left(i); val r = right(i)
            splitter.process(l, r)
            if (i >= settle) {
                inLeft.add(l); bedLeft.add(splitter.bedLeft); bedRight.add(splitter.bedRight); center.add(splitter.center)
            }
            if (i % 1024 == 1023) splitter.updateUsability(1024)
        }
        return mapOf(
            "in" to inLeft.value, "bedL" to bedLeft.value, "bedR" to bedRight.value,
            "center" to center.value, "usable" to splitter.usable.toDouble(),
        )
    }

    @Test
    fun bedPlusCenterAlwaysEqualsTheOriginalSample() {
        val splitter = SpeechCenterSplitter(rate)
        val random = java.util.Random(7)
        repeat(20_000) {
            val l = random.nextFloat() * 2f - 1f
            val r = random.nextFloat() * 2f - 1f
            splitter.process(l, r)
            assertEquals(l, splitter.bedLeft + splitter.center, 1e-5f)
            assertEquals(r, splitter.bedRight + splitter.center, 1e-5f)
        }
    }

    @Test
    fun centeredSpeechBandVoiceIsRemovedFromTheBed() {
        // 1kHz 居中（左右一样）= 解说人声
        val out = run(left = { sine(1000.0, it) }, right = { sine(1000.0, it) })
        assertTrue("bed rms=${out["bedL"]} in=${out["in"]}", out["bedL"]!! < out["in"]!! * 0.2)   // 至少 14dB
        assertEquals(out["bedL"]!!, out["bedR"]!!, 1e-4)
        assertTrue(out["center"]!! > out["in"]!! * 0.8)
    }

    @Test
    fun stereoMusicOnTheSidesStaysInTheBed() {
        // 左右各一个不同频率的音 = 完全分到两侧的立体声音乐。居中扣除对硬声像的内容会损失约 3dB
        // 并在另一侧留一点反相的"影子"，这是中置消除的固有代价；这里只要求音乐主体还在。
        val out = run(left = { sine(440.0, it) }, right = { sine(660.0, it) })
        assertTrue("bed rms=${out["bedL"]} in=${out["in"]}", out["bedL"]!! > out["in"]!! * 0.6)
        assertTrue(out["bedR"]!! > out["in"]!! * 0.6)
    }

    @Test
    fun bassBelowTheVoiceBandIsKept() {
        val out = run(left = { sine(40.0, it) }, right = { sine(40.0, it) })
        assertTrue("bed rms=${out["bedL"]} in=${out["in"]}", out["bedL"]!! > out["in"]!! * 0.9)
    }

    @Test
    fun highFrequenciesAboveTheVoiceBandAreKept() {
        val out = run(left = { sine(15_000.0, it) }, right = { sine(15_000.0, it) })
        assertTrue("bed rms=${out["bedL"]} in=${out["in"]}", out["bedL"]!! > out["in"]!! * 0.8)
    }

    @Test
    fun voiceOverStereoMusicKeepsTheMusicAndDropsTheVoice() {
        val out = run(
            seconds = 3,
            left = { sine(1000.0, it, 0.4f) + sine(440.0, it, 0.2f) },
            right = { sine(1000.0, it, 0.4f) + sine(660.0, it, 0.2f) },
        )
        // 床里只剩 ~0.2 幅度的音乐（约 0.14 RMS）+ 一点人声残留，应远小于原信号（~0.32 RMS）
        val inRms = out["in"]!!
        val bed = out["bedL"]!!
        assertTrue("bed=$bed in=$inRms", bed < inRms * 0.6)
        assertTrue("bed=$bed", bed > 0.1)
    }

    @Test
    fun dualMonoSourceIsNotUsableButStereoSourceIs() {
        val mono = run(seconds = 8, left = { sine(1000.0, it) + sine(300.0, it, 0.3f) }, right = { sine(1000.0, it) + sine(300.0, it, 0.3f) })
        assertTrue("usable=${mono["usable"]}", mono["usable"]!! < 0.05)

        val stereo = run(seconds = 8, left = { sine(440.0, it) }, right = { sine(660.0, it) })
        assertTrue("usable=${stereo["usable"]}", stereo["usable"]!! > 0.9)
    }

    @Test
    fun silenceKeepsThePreviousUsabilityInsteadOfCollapsingIt() {
        val splitter = SpeechCenterSplitter(rate)
        for (i in 0 until 4 * rate) {
            splitter.process(sine(440.0, i), sine(660.0, i))
            if (i % 1024 == 1023) splitter.updateUsability(1024)
        }
        val before = splitter.usable
        assertTrue(before > 0.9f)
        // 静音一段时间（能量衰减到噪声底以下），可用度不应被当成"单声道"清零
        for (i in 0 until 6 * rate) {
            splitter.process(0f, 0f)
            if (i % 1024 == 1023) splitter.updateUsability(1024)
        }
        assertTrue("usable=${splitter.usable}", splitter.usable > 0.5f)
    }

    @Test
    fun invalidSamplesCannotPoisonTheFilterState() {
        val splitter = SpeechCenterSplitter(rate)
        splitter.process(Float.NaN, Float.POSITIVE_INFINITY)
        repeat(2_000) { i ->
            splitter.process(sine(500.0, i), sine(500.0, i))
            assertTrue(splitter.bedLeft.isFinite() && splitter.bedRight.isFinite() && splitter.center.isFinite())
        }
        assertTrue(abs(splitter.center) <= 1f)
    }

    @Test
    fun resetClearsStateAndUsability() {
        val splitter = SpeechCenterSplitter(rate)
        for (i in 0 until 2 * rate) {
            splitter.process(sine(440.0, i), sine(660.0, i))
            if (i % 1024 == 1023) splitter.updateUsability(1024)
        }
        assertTrue(splitter.usable > 0.5f)
        splitter.reset()
        assertEquals(0f, splitter.usable, 0f)
        splitter.process(0f, 0f)
        assertEquals(0f, splitter.bedLeft, 0f)
    }

    @Test
    fun lowSampleRatesStillBuildValidFilters() {
        val splitter = SpeechCenterSplitter(16_000)
        repeat(4_000) { i ->
            val x = (0.5 * sin(2.0 * PI * 1000.0 * i / 16_000)).toFloat()
            splitter.process(x, x)
            assertTrue(splitter.bedLeft.isFinite())
        }
    }
}
