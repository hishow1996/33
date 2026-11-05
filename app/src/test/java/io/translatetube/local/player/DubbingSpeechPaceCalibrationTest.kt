package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingSpeechPaceCalibrationTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-6) =
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= eps)

    @Test
    fun sanitizeKeepsScaleInsideSafeBounds() {
        near(1.0, DubbingSpeechPaceCalibration.sanitize(Double.NaN))
        near(1.0, DubbingSpeechPaceCalibration.sanitize(0.0))
        near(1.0, DubbingSpeechPaceCalibration.sanitize(-2.0))
        near(0.5, DubbingSpeechPaceCalibration.sanitize(0.01))
        near(2.0, DubbingSpeechPaceCalibration.sanitize(50.0))
        near(1.3, DubbingSpeechPaceCalibration.sanitize(1.3))
    }

    @Test
    fun slowerVoicePullsTheScaleUpFasterVoicePullsItDown() {
        val slower = DubbingSpeechPaceCalibration.update(1.0, 3_000L, 1.0f, 2_000L)
        assertTrue(slower > 1.0 && slower < 1.5)
        val faster = DubbingSpeechPaceCalibration.update(1.0, 1_500L, 1.0f, 2_000L)
        assertTrue(faster < 1.0 && faster > 0.75)
    }

    @Test
    fun repeatedSamplesConvergeToTheMeasuredRatio() {
        var scale = 1.0
        repeat(40) { scale = DubbingSpeechPaceCalibration.update(scale, 2_600L, 1.0f, 2_000L) }
        near(1.3, scale, 0.01)
    }

    @Test
    fun synthesisSpeedIsNormalizedBackToOneTimesSpeed() {
        // 2 倍语速合成出 1.5s，相当于 1 倍语速 3.0s：与 1 倍语速 3.0s 的样本等价。
        val fast = DubbingSpeechPaceCalibration.update(1.0, 1_500L, 2.0f, 2_000L)
        val normal = DubbingSpeechPaceCalibration.update(1.0, 3_000L, 1.0f, 2_000L)
        near(normal, fast)
    }

    @Test
    fun shortOrAbnormalSamplesAreIgnored() {
        // 太短（固定开销占比大）。
        near(1.2, DubbingSpeechPaceCalibration.update(1.2, 500L, 1.0f, 2_000L))
        near(1.2, DubbingSpeechPaceCalibration.update(1.2, 2_000L, 1.0f, 500L))
        // 合成被截断/几乎全静音：比例远低于正常范围。
        near(1.2, DubbingSpeechPaceCalibration.update(1.2, 900L, 1.0f, 5_000L))
        // 比例高得离谱（例如读成了别的内容）。
        near(1.2, DubbingSpeechPaceCalibration.update(1.2, 20_000L, 1.0f, 2_000L))
        // 非法语速。
        near(1.2, DubbingSpeechPaceCalibration.update(1.2, 2_000L, 0f, 2_000L))
        near(1.2, DubbingSpeechPaceCalibration.update(1.2, 2_000L, Float.NaN, 2_000L))
    }

    @Test
    fun updatedScaleNeverLeavesTheBounds() {
        var scale = 1.0
        repeat(200) { scale = DubbingSpeechPaceCalibration.update(scale, 6_000L, 1.0f, 2_000L) }
        assertEquals(2.0, scale, 0.0)
        repeat(400) { scale = DubbingSpeechPaceCalibration.update(scale, 800L, 1.0f, 2_000L) }
        assertEquals(0.5, scale, 0.0)
    }
}
