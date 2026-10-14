package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingQueueOrderTest {
    @Test
    fun currentAndNextTwoLinesMoveAheadOfEarlierQueuedWork() {
        assertEquals(
            listOf(20, 21, 22, 3, 4, 9),
            DubbingQueueOrder.prioritize(
                queued = listOf(3, 4, 9, 20, 21, 22),
                currentIndex = 20,
                totalLines = 30,
            ),
        )
    }

    @Test
    fun missingCurrentLineDoesNotDropOrDuplicateOtherQueuedLines() {
        val original = listOf(1, 2, 5, 8, 9)
        val result = DubbingQueueOrder.prioritize(original, 6, 12)
        assertEquals(listOf(8, 9, 1, 2, 5), result)
        assertEquals(original.sorted(), result.sorted())
    }

    @Test
    fun duplicateQueuedIndexesDoNotDuplicateSynthesisWork() {
        assertEquals(
            listOf(3, 4, 2, 8),
            DubbingQueueOrder.prioritize(
                queued = listOf(4, 4, 2, 3, 2, 8),
                currentIndex = 3,
                totalLines = 10,
                lookAhead = 1,
            ),
        )
    }

    @Test
    fun zeroLookAheadPromotesOnlyCurrentCueAndPreservesRemainingOrder() {
        assertEquals(
            listOf(5, 2, 7, 1),
            DubbingQueueOrder.prioritize(
                queued = listOf(2, 7, 5, 1),
                currentIndex = 5,
                totalLines = 8,
                lookAhead = 0,
            ),
        )
    }

    @Test
    fun invalidTargetLeavesQueueUnchanged() {
        val original = listOf(0, 2, 4)
        assertEquals(original, DubbingQueueOrder.prioritize(original, -1, 5))
        assertEquals(original, DubbingQueueOrder.prioritize(original, 9, 5))
    }
}
