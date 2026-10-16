package io.translatetube.local.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEdgeSilenceTrimmerTest {

    private val rate = 1000 // 1 kHz，方便按毫秒数样本

    @Test
    fun trimsLeadingAndTrailingSilenceKeepingMargin() {
        // 300ms 静音 + 100ms 语音 + 300ms 静音
        val samples = FloatArray(700) { i ->
            when {
                i < 300 -> 0f
                i < 400 -> 0.5f
                else -> 0f
            }
        }
        val trimmed = TtsEdgeSilenceTrimmer.trim(samples, rate)
        // 静音边界 300/400 各内收 25ms 余量：[275, 425) → 150ms
        assertTrue("trimmed size=${trimmed.size}", trimmed.size == 150)
        assertArrayEquals(FloatArray(25) { 0f }, trimmed.copyOfRange(0, 25), 0f)
        assertArrayEquals(FloatArray(50) { 0.5f }, trimmed.copyOfRange(25, 75), 0f)
        assertArrayEquals(FloatArray(50) { 0.5f }, trimmed.copyOfRange(75, 125), 0f)
        assertArrayEquals(FloatArray(25) { 0f }, trimmed.copyOfRange(125, 150), 0f)
    }

    @Test
    fun fullySilentOutputIsNotTrimmedToAvoidTimelineCollapse() {
        val samples = FloatArray(500)
        assertSame(samples, TtsEdgeSilenceTrimmer.trim(samples, rate))
    }

    @Test
    fun trimIsCappedAtMaxTrimMs() {
        // 2 秒静音 + 短语音：首部最多只裁 400ms + 25ms 余量
        val samples = FloatArray(2000 + 50) { i ->
            if (i >= 2000) 0.5f else 0f
        }
        val trimmed = TtsEdgeSilenceTrimmer.trim(samples, rate)
        // from = 400-25 = 375；to = min(2050+25, 2050) = 2050 → 1675ms
        assertTrue("trimmed size=${trimmed.size}", trimmed.size == 1675)
    }

    @Test
    fun longSilenceOnBothSidesIsCappedOnEachEdge() {
        // 1s 静音 + 30ms 语音 + 1s 静音：两侧各裁 400ms 后各退回 25ms 余量
        val samples = FloatArray(1000 + 30 + 1000) { i ->
            if (i in 1000 until 1030) 0.6f else 0f
        }
        val trimmed = TtsEdgeSilenceTrimmer.trim(samples, rate)
        // 保留 = (1000-400+25) + 30 + (1000-400+25) = 1280
        assertTrue("trimmed size=${trimmed.size}", trimmed.size == 1280)
    }

    @Test
    fun loudSignalPassthroughReturnsSameArray() {
        val samples = FloatArray(100) { 0.9f }
        assertSame(samples, TtsEdgeSilenceTrimmer.trim(samples, rate))
    }

    @Test
    fun invalidSampleRateOrEmptyArrayIsReturnedUnchanged() {
        val empty = FloatArray(0)
        assertSame(empty, TtsEdgeSilenceTrimmer.trim(empty, rate))
        val samples = FloatArray(10) { 0.3f }
        assertSame(samples, TtsEdgeSilenceTrimmer.trim(samples, 0))
    }

@Test
    fun silenceScanCanBeCancelledDuringLongInput() {
        val samples = FloatArray(100_000)
        var checks = 0
        val error = runCatching {
            TtsEdgeSilenceTrimmer.trim(samples, rate) {
                checks++
                if (checks == 3) throw java.util.concurrent.CancellationException("stale system TTS audio")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertTrue(checks >= 3)
    }

    @Test
    fun trimmingCopyCanBeCancelledWithoutPublishingPartialAudio() {
        val samples = FloatArray(100_000) { index ->
            if (index in 45_000..55_000) 0.5f else 0f
        }
        var checks = 0
        val error = runCatching {
            TtsEdgeSilenceTrimmer.trim(samples, rate) {
                checks++
                if (checks == 5) throw java.util.concurrent.CancellationException("copy cancelled")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }

    @Test
    fun cancellationCheckRunsEvenForEmptyOrPassthroughAudio() {
        val error = runCatching {
            TtsEdgeSilenceTrimmer.trim(floatArrayOf(0.5f), rate) {
                throw java.util.concurrent.CancellationException("already stale")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }

}
