package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOnsetDetectorTest {
    private val sampleRate = 16_000

    /** [quietMs] 毫秒的底噪，然后是 [loudMs] 毫秒的"人声"（440Hz 正弦）。 */
    private fun clip(quietMs: Int, loudMs: Int, quietLevel: Float = 0.001f): FloatArray {
        val total = (quietMs + loudMs) * sampleRate / 1000
        val quietSamples = quietMs * sampleRate / 1000
        return FloatArray(total) { i ->
            if (i < quietSamples) quietLevel * (if (i % 2 == 0) 1f else -1f)
            else 0.3f * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate).toFloat()
        }
    }

    @Test
    fun findsTheMomentSoundStartsWhenTheVadStartIsLate() {
        // 片段从 1000ms 处开始；人声在片段内 1000ms 处起（= 媒体时间 2000ms），VAD 起点标成 2400ms
        val samples = clip(quietMs = 1000, loudMs = 1000)
        val onset = SpeechOnsetDetector.findOnsetMs(samples, sampleRate, sliceStartMs = 1000L, vadStartMs = 2400L)
        assertNotNull(onset)
        assertEquals(2000L, onset!!, 20L)
    }

    @Test
    fun returnsNullWhenSoundWasAlreadyPlayingBeforeTheSearchWindow() {
        // 整段都有持续的响声（片头音乐）：量不出开口点，保持原来的起点
        val samples = clip(quietMs = 0, loudMs = 2000)
        val onset = SpeechOnsetDetector.findOnsetMs(samples, sampleRate, sliceStartMs = 1000L, vadStartMs = 2400L)
        assertNull(onset)
    }

    @Test
    fun returnsNullForSilence() {
        val samples = FloatArray(2 * sampleRate)
        assertNull(SpeechOnsetDetector.findOnsetMs(samples, sampleRate, 0L, 1000L))
    }

    @Test
    fun ignoresAShortClickBeforeTheRealSpeech() {
        val samples = clip(quietMs = 1000, loudMs = 1000)
        // 在搜索范围内的静音里放一个 20ms 的爆音（不够持续，不能算开口）
        val clickStart = 700 * sampleRate / 1000
        for (i in clickStart until clickStart + 20 * sampleRate / 1000) samples[i] = 0.5f
        val onset = SpeechOnsetDetector.findOnsetMs(samples, sampleRate, sliceStartMs = 1000L, vadStartMs = 2400L)
        assertNotNull(onset)
        assertEquals(2000L, onset!!, 20L)
    }

    @Test
    fun speechAtTheVeryStartOfTheStreamStartsAtZero() {
        val samples = clip(quietMs = 0, loudMs = 1500)
        val onset = SpeechOnsetDetector.findOnsetMs(samples, sampleRate, sliceStartMs = 0L, vadStartMs = 100L)
        // 全程都响，底噪估计也高 -> 没有足够把握，宁可不动
        assertTrue(onset == null || onset <= 20L)
    }

    @Test
    fun readRangeIsBoundedByTheStreamAndTheSearchWindow() {
        val (from, to) = SpeechOnsetDetector.readRangeMs(vadStartMs = 10_000L, durationMs = 60_000L)
        assertEquals(10_000L - SpeechOnsetDetector.CONTEXT_BEFORE_MS, from)
        assertTrue(to > 10_000L + SpeechOnsetDetector.SEARCH_AFTER_MS)

        val (earlyFrom, earlyTo) = SpeechOnsetDetector.readRangeMs(vadStartMs = 300L, durationMs = 600L)
        assertEquals(0L, earlyFrom)
        assertEquals(600L, earlyTo)
    }
}
