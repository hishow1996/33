package io.translatetube.local.player

import org.junit.Assert.assertEquals
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
}
