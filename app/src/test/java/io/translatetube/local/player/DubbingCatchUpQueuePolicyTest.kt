package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCatchUpQueuePolicyTest {
    @Test
    fun interruptedSentenceIsPlacedBeforeLaterMissedCues() {
        val queue = linkedSetOf(4, 5, 6)

        DubbingCatchUpQueuePolicy.enqueue(
            queue = queue,
            index = 3,
            prioritizeFirst = true,
        )

        assertEquals(listOf(3, 4, 5, 6), queue.toList())
    }

    @Test
    fun prioritizingAnAlreadyQueuedSentenceMovesItWithoutDuplicatingIt() {
        val queue = linkedSetOf(4, 5, 6)

        DubbingCatchUpQueuePolicy.enqueue(
            queue = queue,
            index = 5,
            prioritizeFirst = true,
        )

        assertEquals(listOf(5, 4, 6), queue.toList())
        assertEquals(queue.size, queue.toSet().size)
    }

    @Test
    fun ordinaryEnqueueKeepsExistingCatchUpOrderAndRemainsUnique() {
        val queue = linkedSetOf(4, 5)

        DubbingCatchUpQueuePolicy.enqueue(queue, index = 5)
        DubbingCatchUpQueuePolicy.enqueue(queue, index = 6)

        assertEquals(listOf(4, 5, 6), queue.toList())
    }

    @Test
    fun crossedCuesAreReturnedInStartTimeOrderAndExcludeTheActiveCue() {
        val starts = listOf(0L, 240L, 100L, 180L, 240L, 300L)
        val crossed = DubbingCatchUpQueuePolicy.crossedCueIndexes(
            cueCount = starts.size,
            previousPositionMs = 90L,
            positionMs = 260L,
            activeDubbingIndex = 3,
            cueStartMs = starts::get,
            isPlayableCue = { true },
        )

        assertEquals(listOf(2, 1, 4), crossed)
    }

    @Test
    fun crossedCueWindowUsesExclusivePreviousAndInclusiveCurrentBoundary() {
        val starts = listOf(100L, 200L, 300L)
        val crossed = DubbingCatchUpQueuePolicy.crossedCueIndexes(
            cueCount = starts.size,
            previousPositionMs = 100L,
            positionMs = 200L,
            activeDubbingIndex = -1,
            cueStartMs = starts::get,
            isPlayableCue = { true },
        )

        assertEquals(listOf(1), crossed)
    }

    @Test
    fun queueConsumesEarliestNewlyCapturedCueBeforeTheCurrentPlayheadCue() {
        val queue = linkedSetOf<Int>()
        val starts = listOf(0L, 100L, 200L, 300L)
        val crossed = DubbingCatchUpQueuePolicy.crossedCueIndexes(
            cueCount = starts.size,
            previousPositionMs = 50L,
            positionMs = 350L,
            activeDubbingIndex = 0,
            cueStartMs = starts::get,
            isPlayableCue = { true },
        )
        crossed.forEach(queue::add)

        val currentPlayheadCue = 3
        assertTrue(queue.firstOrNull() != currentPlayheadCue)
        assertEquals(1, queue.firstOrNull())
    }
}
