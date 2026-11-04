package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingFitPolicyTest {
    private val rate = 24_000

    private fun samples(ms: Long): Int = (rate * ms / 1_000L).toInt()

    @Test
    fun audioThatFitsTheWindowIsNeverStretched() {
        // 旧实现会把 2s 的语音在 3s 窗口里拉长 1.5 倍；现在必须保持原速。
        assertEquals(1f, DubbingFitPolicy.synthesisRatio(samples(2_000), rate, 3_000), 0f)
        assertEquals(1f, DubbingFitPolicy.synthesisRatio(samples(500), rate, 10_000), 0f)
        assertEquals(1f, DubbingFitPolicy.synthesisRatio(samples(3_000), rate, 3_000), 0f)
    }

    @Test
    fun overlongAudioIsCompressedToLeaveASmallMargin() {
        val ratio = DubbingFitPolicy.synthesisRatio(samples(3_000), rate, 2_400)
        assertTrue("should compress, got $ratio", ratio < 1f)
        // 压缩后时长 ≈ 窗口的 97%
        assertEquals(2_400 * 0.97, 3_000 * ratio.toDouble(), 5.0)
    }

    @Test
    fun compressionIsCappedAtTheNaturalSoundingLimit() {
        val ratio = DubbingFitPolicy.synthesisRatio(samples(6_000), rate, 2_000)
        assertEquals(1f / DubbingFitPolicy.MAX_COMPRESS, ratio, 0.001f)
    }

    @Test
    fun negligibleOverrunIsLeftAloneToAvoidProcessingArtifacts() {
        // 超出 2% 不值得变速
        assertEquals(1f, DubbingFitPolicy.synthesisRatio(samples(2_040), rate, 2_000), 0f)
    }

    @Test
    fun invalidInputsDoNothing() {
        assertEquals(1f, DubbingFitPolicy.synthesisRatio(0, rate, 1_000), 0f)
        assertEquals(1f, DubbingFitPolicy.synthesisRatio(100, 0, 1_000), 0f)
        assertEquals(1f, DubbingFitPolicy.synthesisRatio(100, rate, 0), 0f)
    }

    @Test
    fun catchUpIsNotNeededWhenTheSentenceStillEndsInsideTheWindow() {
        // 本句 1s，推迟 0.5s 开口，窗口末尾还有 3s
        assertEquals(1f, DubbingFitPolicy.catchUpRatio(500_000, 1_000_000, 3_000_000), 0f)
    }

    @Test
    fun catchUpCompressesJustEnoughToRepayTheDelay() {
        // 开口时间 1.0s，时长 2.0s，要求 2.4s 前结束 → 允许 1.4s → 比例 0.7，但下限 0.75
        val ratio = DubbingFitPolicy.catchUpRatio(1_000_000, 2_000_000, 2_400_000)
        assertEquals(DubbingFitPolicy.MIN_CATCH_UP_RATIO, ratio, 0.0001f)
        // 只需要还一点：允许 1.7s → 0.85
        assertEquals(0.85f, DubbingFitPolicy.catchUpRatio(1_000_000, 2_000_000, 2_700_000), 0.0001f)
    }

    @Test
    fun catchUpNeverCompressesBeyondTheFloorEvenWhenHopelesslyLate() {
        assertEquals(
            DubbingFitPolicy.MIN_CATCH_UP_RATIO,
            DubbingFitPolicy.catchUpRatio(5_000_000, 2_000_000, 1_000_000),
            0.0001f,
        )
    }

    @Test
    fun tinySavingsAreSkipped() {
        assertEquals(1f, DubbingFitPolicy.catchUpRatio(0, 1_000_000, 980_000), 0f)
    }

    @Test
    fun nativeRetryBudgetScalesWithTheCompressionCap() {
        assertEquals(3_000L, DubbingFitPolicy.nativeRetryBudgetMs(2_000))
        assertEquals(1L, DubbingFitPolicy.nativeRetryBudgetMs(0))
    }
}
