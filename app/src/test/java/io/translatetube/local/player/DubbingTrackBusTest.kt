package io.translatetube.local.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingTrackBusTest {

    @Test
    fun overlappingCuesAreAddedAtTheirMediaFrames() {
        val bus = DubbingTrackBus()
        bus.configure(4)
        val generation = bus.generationToken()

        assertTrue(bus.feed("first", 0L, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 4, generation))
        assertTrue(bus.feed("second", 500_000L, floatArrayOf(0.5f, 0.6f), 4, generation))

        assertArrayEquals(
            floatArrayOf(0.1f, 0.2f, 0.8f, 1.0f),
            bus.readMixWindow(0L, 4),
            0.0001f,
        )
    }

    @Test
    fun refeedingSameCueReplacesItsPreviousBlock() {
        val bus = DubbingTrackBus()
        bus.configure(4)
        val generation = bus.generationToken()

        assertTrue(bus.feed("cue", 0L, floatArrayOf(0.2f, 0.3f), 4, generation))
        assertTrue(bus.feed("cue", 0L, floatArrayOf(0.4f, 0.1f), 4, generation))

        assertArrayEquals(floatArrayOf(0.4f, 0.1f), bus.readMixWindow(0L, 2), 0.0001f)
    }

    @Test
    fun disablingDubbingClearsVoiceAndRejectsStaleFeeds() {
        val bus = DubbingTrackBus()
        bus.configure(4)
        val oldGeneration = bus.generationToken()
        assertTrue(bus.feed("cue", 0L, floatArrayOf(0.8f, 0.8f), 4, oldGeneration))

        bus.setDubbingEnabled(false)

        assertFalse(bus.feed("stale", 0L, floatArrayOf(0.5f), 4, oldGeneration))
        assertArrayEquals(floatArrayOf(0f, 0f), bus.readMixWindow(0L, 2), 0.0001f)
    }

    // ---- 排队挂载（配音句子专用）。采样率取 1000Hz，1 帧 = 1ms，便于推算 ----

    private fun ms(value: Long) = value * 1_000L

    private fun newBus(): DubbingTrackBus = DubbingTrackBus().also { it.configure(1000) }

    @Test
    fun sequencedCueThatOverrunsPushesTheNextOneInsteadOfOverlapping() {
        val bus = newBus()
        val generation = bus.generationToken()
        val first = FloatArray(500) { 0.5f }
        val second = FloatArray(200) { 0.25f }

        assertEquals(0L, bus.feedSequenced("a", 0L, 0L, first, 1000, generation))
        // 第二句本该在 300ms 开口，但第一句要到 500ms 才说完 → 排到 500 + 120ms 间隔
        assertEquals(ms(620), bus.feedSequenced("b", ms(300), 0L, second, 1000, generation))

        val mixed = bus.readMixWindow(0L, 1000)
        assertEquals(0.5f, mixed[250], 0.0001f)
        assertEquals(0.5f, mixed[400], 0.0001f)   // 原来会和第二句叠加成 0.75
        assertEquals(0f, mixed[560], 0.0001f)     // 两句之间的停顿
        assertEquals(0.25f, mixed[700], 0.0001f)
        assertTrue("no frame may ever carry two voices", mixed.all { it <= 0.5f + 0.0001f })
    }

    @Test
    fun chainOfOverrunningCuesNeverSumsIntoDoubleVolume() {
        val bus = newBus()
        val generation = bus.generationToken()
        var cueIndex = 0
        for (desired in longArrayOf(0, 200, 400, 600)) {
            bus.feedSequenced("c${cueIndex++}", ms(desired), 0L, FloatArray(350) { 0.4f }, 1000, generation)
        }
        val mixed = bus.readMixWindow(0L, 3000)
        assertTrue(mixed.max() <= 0.4f + 0.0001f)
        // 四句都完整播出（每句 350 帧，淡入淡出各吃掉一点，仍应有大量满幅样本）
        assertTrue(mixed.count { it > 0.399f } >= 4 * 300)
    }

    @Test
    fun cueFedAfterItsStartPlaysWholeFromJustAheadOfTheRenderHead() {
        val bus = newBus()
        val generation = bus.generationToken()
        bus.readMixWindow(ms(1_000), 1000) // 渲染头推进到 2000ms

        // 期望 1500ms 开口但已经过去：从渲染头 + 100ms 整句播出，而不是从中间切入
        val start = bus.feedSequenced("late", ms(1_500), 0L, FloatArray(300) { 0.6f }, 1000, generation)
        assertEquals(ms(2_100), start)
        val mixed = bus.readMixWindow(ms(2_000), 600)
        assertEquals(0f, mixed[50], 0.0001f)                      // 开口前是静音
        assertEquals(0.6f, mixed[100 + 150], 0.0001f)             // 句子中段
        // 整句 300 帧都播出了（首尾淡入淡出各占几帧）
        assertTrue(mixed.count { it > 0.599f } >= 300 - 6 - 12)
        assertEquals(0f, mixed[100 + 300 + 20], 0.0001f)          // 说完之后又是静音
    }

    @Test
    fun rewindingTheReadHeadLetsStartupCuesKeepTheirOwnTimeAndSurviveTheFlush() {
        val bus = newBus()
        val firstGeneration = bus.generationToken()
        // 播放器预读阶段已经读到 700ms：此后挂载的开场句会被顶到读头之后
        bus.readMixWindow(0L, 700)
        assertEquals(ms(800), bus.feedSequenced("stale", ms(200), 0L, FloatArray(100) { 0.5f }, 1000, firstGeneration))
        bus.removeCuesWithPrefix("stale")
        val generationAfterRemoval = bus.generationToken()

        // 开播前归零读头：同一句按自己的期望时间 200ms 开口，清理前的提交代次已经失效。
        bus.rewindReadHead()
        assertEquals(ms(200), bus.feedSequenced("first", ms(200), 0L, FloatArray(100) { 0.5f }, 1000, generationAfterRemoval))
        assertTrue(bus.hasCue("first"))
        // 播放器 flush 后从头重读：开头这句能混进去
        val mixed = bus.readMixWindow(0L, 400)
        assertEquals(0f, mixed[100], 0.0001f)
        assertEquals(0.5f, mixed[250], 0.0001f)
    }

    @Test
    fun frozenReadHeadIgnoresPrerollReadsWhileStartupCuesAreMounting() {
        val bus = newBus()
        val generation = bus.generationToken()
        bus.readMixWindow(0L, 700)
        bus.rewindReadHead(freezeMs = 5_000L)
        // 挂载期间播放器的预读还在继续：读头不能被推回去
        bus.readMixWindow(700_000L, 300)
        assertEquals(ms(200), bus.feedSequenced("first", ms(200), 0L, FloatArray(100) { 0.5f }, 1000, generation))

        // 不带冻结的 rewind（flush 之后）立即解除冻结，读头重新跟随音频读取
        bus.rewindReadHead()
        bus.readMixWindow(0L, 700)
        assertEquals(ms(800), bus.feedSequenced("late", ms(300), 0L, FloatArray(100) { 0.5f }, 1000, generation))
    }

    @Test
    fun expiredFreezeLetsTheReadHeadAdvanceAgain() {
        val bus = newBus()
        val generation = bus.generationToken()
        bus.rewindReadHead(freezeMs = 1L)
        Thread.sleep(20)
        bus.readMixWindow(0L, 700)
        assertEquals(ms(800), bus.feedSequenced("late", ms(200), 0L, FloatArray(100) { 0.5f }, 1000, generation))
    }

    @Test
    fun cueCallerKnowsIsNotYetDueIsNotMovedEarlier() {
        val bus = newBus()
        val generation = bus.generationToken()
        // 调用方的 notBefore 是当前播放头；期望时间更晚则保持期望时间
        assertEquals(ms(5_000), bus.feedSequenced("future", ms(5_000), ms(1_000), FloatArray(100) { 0.3f }, 1000, generation))
        // 期望时间已过 → 不早于调用方给的播放头
        assertEquals(ms(1_000), bus.feedSequenced("past", ms(200), ms(1_000), FloatArray(100) { 0.3f }, 1000, generation))
    }

    @Test
    fun aCueThatHasStartedPlayingIsNeverReplacedOrMoved() {
        val bus = newBus()
        val generation = bus.generationToken()
        assertEquals(0L, bus.feedSequenced("x", 0L, 0L, FloatArray(1000) { 0.5f }, 1000, generation))
        bus.readMixWindow(0L, 500) // 已播到一半

        val again = bus.feedSequenced("x", 0L, 0L, FloatArray(1000) { 0.9f }, 1000, generation)
        assertEquals(0L, again) // 返回原来的开口时间，内容不变
        assertEquals(0.5f, bus.readMixWindow(ms(500), 100)[10], 0.0001f)
    }

    @Test
    fun projectedStartMatchesWhereTheCueActuallyLands() {
        val bus = newBus()
        val generation = bus.generationToken()
        bus.feedSequenced("a", 0L, 0L, FloatArray(500) { 0.5f }, 1000, generation)

        val projected = bus.projectStartUs("b", ms(300), 0L, ms(200))
        assertEquals(ms(620), projected)
        assertFalse("projection must not mount anything", bus.hasCue("b"))
        assertEquals(projected, bus.feedSequenced("b", ms(300), 0L, FloatArray(200) { 0.25f }, 1000, generation))
        assertTrue(bus.hasCue("b"))
    }

    @Test
    fun removingCuesByPrefixDropsOnlyThatEnginesVoice() {
        val bus = newBus()
        val generation = bus.generationToken()
        bus.feedSequenced("7:1", 0L, 0L, FloatArray(100) { 0.5f }, 1000, generation)
        bus.feedSequenced("7:2", ms(1_000), 0L, FloatArray(100) { 0.5f }, 1000, generation)
        bus.feedSequenced("8:1", ms(2_000), 0L, FloatArray(100) { 0.7f }, 1000, generation)

        bus.removeCuesWithPrefix("7:")
        val nextGeneration = bus.generationToken()

        assertFalse(bus.hasCue("7:1"))
        assertFalse(bus.hasCue("7:2"))
        assertTrue(bus.hasCue("8:1"))
        // The unrelated installed cue remains audible, but its future refeed must use the new token.
        assertEquals(-1L, bus.feedSequenced("8:2", ms(2_000), 0L, FloatArray(10) { 0.2f }, 1000, generation))
        assertTrue(bus.feedSequenced("8:2", ms(2_000), 0L, FloatArray(10) { 0.2f }, 1000, nextGeneration) >= 0L)
        val mixed = bus.readMixWindow(0L, 3000)
        assertEquals(0f, mixed[50], 0f)
        assertEquals(0f, mixed[1050], 0f)
        assertEquals(0.7f, mixed[2050], 0.0001f)
    }

    @Test
    fun removingACueLetsLaterCuesReturnToTheirOwnTimeOnNextFeed() {
        val bus = newBus()
        val generation = bus.generationToken()
        bus.feedSequenced("long", 0L, 0L, FloatArray(800) { 0.5f }, 1000, generation)
        assertEquals(ms(920), bus.feedSequenced("next", ms(300), 0L, FloatArray(100) { 0.5f }, 1000, generation))
        // 把前一句换成短版本：后一句应回到自己的时间。清理同时使旧提交代次失效。
        bus.removeCuesWithPrefix("long")
        assertEquals(-1L, bus.feedSequenced("next", ms(300), 0L, FloatArray(100) { 0.5f }, 1000, generation))
        val nextGeneration = bus.generationToken()
        assertEquals(ms(300), bus.feedSequenced("next", ms(300), 0L, FloatArray(100) { 0.5f }, 1000, nextGeneration))
    }

    @Test
    fun blockEdgesFadeToAvoidClicksWhileTheMiddleIsUntouched() {
        val bus = newBus() // 1000Hz：淡入 6 帧，淡出 12 帧
        val generation = bus.generationToken()
        assertTrue(bus.feed("k", 0L, FloatArray(100) { 1f }, 1000, generation))
        val mixed = bus.readMixWindow(0L, 100)
        assertEquals(0f, mixed[0], 0.0001f)
        assertEquals(0.5f, mixed[3], 0.0001f)
        assertEquals(1f, mixed[6], 0.0001f)
        assertEquals(1f, mixed[50], 0.0001f)
        assertEquals(1f, mixed[87], 0.0001f)
        assertEquals(1f / 12f, mixed[99], 0.0001f)
    }

    @Test
    fun prefixRemovalInvalidatesInFlightFeedEvenWhenNoMatchingCueWasInstalledYet() {
        val bus = newBus()
        val staleGeneration = bus.generationToken()

        // Simulate an async prefeed still resampling when cleanup runs; no block existed yet.
        bus.removeCuesWithPrefix("old-engine:")

        assertTrue(bus.generationToken() > staleGeneration)
        assertEquals(
            -1L,
            bus.feedSequenced(
                "old-engine:1",
                0L,
                0L,
                FloatArray(20) { 0.5f },
                1000,
                staleGeneration,
            ),
        )
        assertFalse(bus.hasCue("old-engine:1"))
    }

    @Test
    fun sequencedFeedIsRejectedAfterDubbingIsDisabledOrGenerationChanged() {
        val bus = newBus()
        val oldGeneration = bus.generationToken()
        bus.reset()
        assertEquals(-1L, bus.feedSequenced("stale", 0L, 0L, FloatArray(10) { 0.5f }, 1000, oldGeneration))
        bus.setDubbingEnabled(false)
        assertEquals(-1L, bus.feedSequenced("off", 0L, 0L, FloatArray(10) { 0.5f }, 1000, bus.generationToken()))
    }

    @Test
    fun resamplingToTheSinkRateKeepsLevelAndScalesLength() {
        val bus = DubbingTrackBus().also { it.configure(8) }
        val generation = bus.generationToken()
        assertTrue(bus.feed("up", 0L, FloatArray(4) { 0.5f }, 4, generation))

        val mixed = bus.readMixWindow(0L, 10)
        for (i in 0 until 8) assertEquals(0.5f, mixed[i], 0.0001f)
        assertEquals(0f, mixed[8], 0.0001f)
    }

    @Test
    fun resamplingFollowsASmoothRampAndNeverOvershootsWildly() {
        val bus = DubbingTrackBus().also { it.configure(12) }
        val generation = bus.generationToken()
        val ramp = FloatArray(6) { it * 0.1f }
        assertTrue(bus.feed("ramp", 0L, ramp, 6, generation))

        val mixed = bus.readMixWindow(0L, 12)
        // 内部点：Catmull-Rom 对直线精确还原，输出位置 k 对应输入位置 k/2。
        for (k in 2..7) assertEquals(0.05f * k, mixed[k], 0.0001f)
        assertTrue(mixed.all { it in -0.01f..0.52f })
    }
}
