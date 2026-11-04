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
    fun findsTheMomentSoundStartsWhenTheCaptionIsLate() {
        // 片段从 1000ms 处开始；人声在片段内 1000ms 处起（= 媒体时间 2000ms），字幕起点标成 2400ms
        val samples = clip(quietMs = 1000, loudMs = 1000)
        val onset = SpeechOnsetDetector.findOnsetMs(samples, sampleRate, sliceStartMs = 1000L, captionStartMs = 2400L)
        assertNotNull(onset)
        assertEquals(2000.0, onset!!.toDouble(), 20.0)
    }

    @Test
    fun returnsNullWhenSoundWasAlreadyPlayingBeforeTheSearchWindow() {
        // 整段都有持续的响声（片头音乐）：量不出开口点，保持原来的起点
        val samples = clip(quietMs = 0, loudMs = 2000)
        assertNull(SpeechOnsetDetector.findOnsetMs(samples, sampleRate, sliceStartMs = 1000L, captionStartMs = 2400L))
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
        val onset = SpeechOnsetDetector.findOnsetMs(samples, sampleRate, sliceStartMs = 1000L, captionStartMs = 2400L)
        assertNotNull(onset)
        assertEquals(2000.0, onset!!.toDouble(), 20.0)
    }

    @Test
    fun readRangeIsBoundedByTheStreamAndTheSearchWindow() {
        val (from, to) = SpeechOnsetDetector.readRangeMs(captionStartMs = 10_000L, durationMs = 60_000L)
        assertEquals(10_000L - SpeechOnsetDetector.CONTEXT_BEFORE_MS, from)
        assertTrue(to > 10_000L + SpeechOnsetDetector.SEARCH_AFTER_MS)

        val (earlyFrom, earlyTo) = SpeechOnsetDetector.readRangeMs(captionStartMs = 300L, durationMs = 600L)
        assertEquals(0L, earlyFrom)
        assertEquals(600L, earlyTo)
    }

    // ---- calibratedStartMs：起点 = 实测开口 + 统一提前量 300ms，只往前挪 ----

    @Test
    fun calibratedStartIsOnsetPlusTheSharedLead() {
        // 字幕 2400ms，实测 2000ms -> 2300ms；配音在 起点-300 = 2000ms 开口
        assertEquals(2300L, SpeechOnsetDetector.calibratedStartMs(2000L, 2400L, 6000L, 7000L))
    }

    @Test
    fun calibrationNeverPushesTheFirstCueLater() {
        // 字幕本来就比实测早或差不多：不动
        assertNull(SpeechOnsetDetector.calibratedStartMs(2500L, 2400L, 6000L, 7000L))
        assertNull(SpeechOnsetDetector.calibratedStartMs(2090L, 2400L, 6000L, 7000L)) // 2390，只差 10ms
    }

    @Test
    fun calibrationRefusesToCrossTheEndOrTheNextCue() {
        assertNull(SpeechOnsetDetector.calibratedStartMs(1000L, 2400L, 1350L, null)) // 1300 >= 1350-100
        assertNull(SpeechOnsetDetector.calibratedStartMs(1000L, 2400L, 6000L, 1380L)) // 越过下一句
        assertEquals(1300L, SpeechOnsetDetector.calibratedStartMs(1000L, 2400L, 6000L, null))
    }
}
