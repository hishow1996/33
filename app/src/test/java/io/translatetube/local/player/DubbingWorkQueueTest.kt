package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingWorkQueueTest {
    @Test
    fun readyWindowIsBoundedWhileBacklogPreservesEveryCue() {
        val queue = DubbingWorkQueue(readyCapacity = 4, maximumPending = 20)
        repeat(12) { assertTrue(queue.offerLast(it)) }

        assertEquals(4, queue.readySize)
        assertEquals(12, queue.totalSize)
        assertTrue(queue.contains(11))

        val drained = mutableListOf<Int>()
        assertEquals(12, queue.drainTo(drained))
        assertEquals((0 until 12).toList(), drained)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun priorityInsertDisplacesOldestReadyTailWithoutDroppingIt() {
        val queue = DubbingWorkQueue(readyCapacity = 3, maximumPending = 10)
        repeat(6) { queue.offerLast(it) }

        assertTrue(queue.offerFirst(99))
        assertEquals(3, queue.readySize)
        assertEquals(7, queue.totalSize)

        val drained = mutableListOf<Int>()
        queue.drainTo(drained)
        assertEquals(listOf(99, 0, 1, 2, 3, 4, 5), drained)
    }

    @Test
    fun queueDoesNotExceedConfiguredTotalBacklogBound() {
        val queue = DubbingWorkQueue(readyCapacity = 2, maximumPending = 3)
        assertTrue(queue.offerLast(1))
        assertTrue(queue.offerLast(2))
        assertTrue(queue.offerLast(3))
        assertFalse(queue.offerLast(4))
        assertEquals(3, queue.totalSize)
    }

    @Test
    fun duplicateCuesAreNotQueuedTwice() {
        val queue = DubbingWorkQueue(readyCapacity = 2, maximumPending = 5)
        assertTrue(queue.offerLast(2))
        assertTrue(queue.offerLast(2))
        assertTrue(queue.offerFirst(2))
        assertEquals(1, queue.totalSize)
        assertEquals(2, queue.pollFirst())
        assertNull(queue.pollFirst())
    }

    @Test
    fun boundedReadyWindowRefillsFromDeferredQueueInFifoOrder() {
        val queue = DubbingWorkQueue(readyCapacity = 2, maximumPending = 8)
        repeat(6) { queue.offerLast(it) }
        val result = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            assertTrue(queue.readySize <= 2)
            result += queue.pollFirst()!!
        }
        assertEquals((0 until 6).toList(), result)
        assertEquals(0, queue.totalSize)
    }
}
