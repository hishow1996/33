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
        val ratio = DubbingFitPolicy.synthesisRatio(samples(2_600), rate, 2_400)
        assertTrue("should compress, got $ratio", ratio < 1f)
        // 压缩后时长 ≈ 窗口的 97%
        assertEquals(2_400 * 0.97, 2_600 * ratio.toDouble(), 5.0)
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
    fun aMerelyLongSentenceIsNotRushedUnlessItIsReallyLate() {
        // 计划 0s 开口，实际只晚 0.5s：不加速，哪怕会超出窗口
        assertEquals(1f, DubbingFitPolicy.catchUpRatio(500_000, 2_000_000, 1_000_000, scheduledStartUs = 0L), 0f)
        // 晚了 2.5s（超过 2 秒容忍）才允许追赶压缩
        assertTrue(DubbingFitPolicy.catchUpRatio(2_500_000, 2_000_000, 3_000_000, scheduledStartUs = 0L) < 1f)
    }

    @Test
    fun largerLagAndLongerSentencesAreCompressedHarder() {
        // 晚了 6s、4s 长句：期限速率超过上限，按最高速率压缩
        assertEquals(
            1f / DubbingFitPolicy.CATCH_UP_MAX_RATE,
            DubbingFitPolicy.catchUpRatio(6_000_000, 4_000_000, 7_000_000, scheduledStartUs = 0L),
            0.001f,
        )
        // 晚了 3s、4s 长句：基础 1.333 倍，长句份额 1.5 → 1.5 倍
        assertEquals(
            1f / 1.5f,
            DubbingFitPolicy.catchUpRatio(3_000_000, 4_000_000, 4_000_000, scheduledStartUs = 0L),
            0.001f,
        )
    }

    @Test
    fun shortSentencesGetAGentleRateWhileLongOnesCarryTheCatchUp() {
        // 同样晚 3s、同样来不及：1s 短句只加速到 1.1 倍，4s 长句 1.5 倍
        val short = DubbingFitPolicy.catchUpRatio(3_000_000, 1_000_000, 3_100_000, scheduledStartUs = 0L)
        val long = DubbingFitPolicy.catchUpRatio(3_000_000, 4_000_000, 3_100_000, scheduledStartUs = 0L)
        assertEquals(1f / 1.1f, short, 0.001f)
        assertEquals(1f / 1.5f, long, 0.001f)
        assertTrue(short > long)
    }

    @Test
    fun adaptiveFloorScalesWithLagAndSentenceLength() {
        // 4s 长句：落后 2s → 基础 1.2，份额 1.5 → 1.3 倍；落后 4s → 超过上限，1.6 倍
        assertEquals(1f / 1.3f, DubbingFitPolicy.adaptiveCatchUpFloor(2_000_000, 4_000_000), 0.001f)
        assertEquals(
            1f / DubbingFitPolicy.CATCH_UP_MAX_RATE,
            DubbingFitPolicy.adaptiveCatchUpFloor(4_000_000, 4_000_000),
            0.001f,
        )
        // 句长在短句和长句之间线性过渡：2.35s 取中点份额 0.9，落后 3s → 1 + 0.333 * 0.9 = 1.3 倍
        assertEquals(1f / 1.3f, DubbingFitPolicy.adaptiveCatchUpFloor(3_000_000, 2_350_000), 0.001f)
    }

    @Test
    fun adaptiveFloorNeverExceedsTheCeilingEvenForHugeLag() {
        assertEquals(
            1f / DubbingFitPolicy.CATCH_UP_MAX_RATE,
            DubbingFitPolicy.adaptiveCatchUpFloor(60_000_000, 10_000_000),
            0.001f,
        )
    }

    @Test
    fun adaptiveFloorDoesNothingWithoutLagOrDuration() {
        assertEquals(1f, DubbingFitPolicy.adaptiveCatchUpFloor(0, 4_000_000), 0f)
        assertEquals(1f, DubbingFitPolicy.adaptiveCatchUpFloor(-500_000, 4_000_000), 0f)
        assertEquals(1f, DubbingFitPolicy.adaptiveCatchUpFloor(5_000_000, 0), 0f)
    }

    @Test
    fun catchUpContinuesBelowToleranceOnlyWhileTheTrackerIsActive() {
        // 晚 1.2s：未在追赶 → 不压；追赶进行中 → 继续压；晚 0.5s → 追上了，不再压
        assertEquals(1f, DubbingFitPolicy.catchUpRatio(1_200_000, 2_000_000, 2_000_000, scheduledStartUs = 0L, catchingUp = false), 0f)
        assertTrue(DubbingFitPolicy.catchUpRatio(1_200_000, 2_000_000, 2_000_000, scheduledStartUs = 0L, catchingUp = true) < 1f)
        assertEquals(1f, DubbingFitPolicy.catchUpRatio(500_000, 2_000_000, 1_000_000, scheduledStartUs = 0L, catchingUp = true), 0f)
    }

    @Test
    fun tinySavingsAreSkipped() {
        assertEquals(1f, DubbingFitPolicy.catchUpRatio(0, 1_000_000, 980_000), 0f)
    }

    @Test
    fun nativeRetryBudgetScalesWithTheCompressionCap() {
        assertEquals(2_300L, DubbingFitPolicy.nativeRetryBudgetMs(2_000))
        assertEquals(1L, DubbingFitPolicy.nativeRetryBudgetMs(0))
    }
}
