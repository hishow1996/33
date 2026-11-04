package io.translatetube.local.player

import io.translatetube.local.player.DubbingCueLayout.Item
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCueLayoutTest {
    private fun item(desired: Long, length: Long, notBefore: Long = Long.MIN_VALUE, frozen: Long? = null) =
        Item(desired, length, notBefore, frozen)

    @Test
    fun cuesThatFitStayAtTheirDesiredTime() {
        val starts = DubbingCueLayout.layout(
            listOf(item(0, 100), item(200, 100), item(400, 100)),
            gapFrames = 10,
            floorFrame = Long.MIN_VALUE,
        )
        assertArrayEquals(longArrayOf(0, 200, 400), starts)
    }

    @Test
    fun anOverrunningCuePushesTheNextOneBehindItInsteadOfOverlapping() {
        // 第一句长 300（超出到 300），第二句本该在 200 开口 → 排到 300 + 间隔 10
        val starts = DubbingCueLayout.layout(
            listOf(item(0, 300), item(200, 100)),
            gapFrames = 10,
            floorFrame = Long.MIN_VALUE,
        )
        assertArrayEquals(longArrayOf(0, 310), starts)
    }

    @Test
    fun delayCascadesThroughAChainButRecoversWhenThereIsSlack() {
        val starts = DubbingCueLayout.layout(
            listOf(item(0, 250), item(200, 100), item(300, 50), item(900, 50)),
            gapFrames = 10,
            floorFrame = Long.MIN_VALUE,
        )
        // 0..250, 260..360, 370..420, 第四句有富余，回到原位 900
        assertArrayEquals(longArrayOf(0, 260, 370, 900), starts)
    }

    @Test
    fun noTwoCuesEverOverlapRegardlessOfInputOrder() {
        val items = listOf(item(500, 120), item(0, 400), item(450, 90), item(460, 200), item(1000, 10))
        val starts = DubbingCueLayout.layout(items, gapFrames = 5, floorFrame = Long.MIN_VALUE)
        val spans = items.indices.map { starts[it] to starts[it] + items[it].lengthFrames }.sortedBy { it.first }
        for (k in 1 until spans.size) {
            assertTrue("overlap between ${spans[k - 1]} and ${spans[k]}", spans[k].first >= spans[k - 1].second + 5)
        }
        // 没有任何一句比期望时间更早开口
        for (i in items.indices) assertTrue(starts[i] >= items[i].desiredFrame)
    }

    @Test
    fun aLateCueIsPlayedWholeFromTheFloorNotClippedAtItsHead() {
        // 渲染头在 1000，该句期望 400 开口（已过期）→ 从 1000 + 余量整句播出
        val starts = DubbingCueLayout.layout(
            listOf(item(400, 300)),
            gapFrames = 10,
            floorFrame = 1_050,
        )
        assertArrayEquals(longArrayOf(1_050), starts)
    }

    @Test
    fun aCueThatIsAlreadyPlayingNeverMovesAndOthersWaitForIt() {
        val starts = DubbingCueLayout.layout(
            listOf(item(0, 600, frozen = 100), item(300, 100), item(2_000, 50)),
            gapFrames = 20,
            floorFrame = 450,
        )
        assertEquals(100L, starts[0])          // 固定
        assertEquals(720L, starts[1])          // 在正在播的那句结束（700）+ 间隔之后
        assertEquals(2_000L, starts[2])        // 后面有富余，不受影响
    }

    @Test
    fun perCueNotBeforeIsRespected() {
        val starts = DubbingCueLayout.layout(
            listOf(item(0, 100, notBefore = 50)),
            gapFrames = 0,
            floorFrame = Long.MIN_VALUE,
        )
        assertArrayEquals(longArrayOf(50), starts)
    }

    @Test
    fun emptyInputIsFine() {
        assertEquals(0, DubbingCueLayout.layout(emptyList(), 10, Long.MIN_VALUE).size)
    }
}
