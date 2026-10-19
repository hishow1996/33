package io.translatetube.local.player

import java.util.ArrayDeque
import java.util.BitSet

/**
 * Bounded ready queue for the next synthesis jobs plus a capped FIFO backlog of cue indexes.
 *
 * PCM itself remains in the existing bounded LRU/disk cache. The ready window and deferred FIFO
 * use array-backed storage rather than one linked-list node per subtitle; the backlog is bounded
 * by the same hard input-cue limit as the timeline.
 */
internal class DubbingWorkQueue(
    private val readyCapacity: Int = DEFAULT_READY_CAPACITY,
    private val maximumPending: Int = DubbingTimeline.MAX_INPUT_CUE_COUNT,
) {
    private val ready = ArrayDeque<Int>(readyCapacity)
    private val deferred = ArrayDeque<Int>()
    /** O(1) membership checks keep bulk cache scans linear for very long subtitle files. */
    private val queuedIndexes = BitSet()

    init {
        require(readyCapacity > 0) { "readyCapacity must be positive" }
        require(maximumPending >= readyCapacity) { "maximumPending must cover readyCapacity" }
    }

    @Synchronized
    fun offerLast(index: Int): Boolean {
        if (index !in 0 until DubbingTimeline.MAX_INPUT_CUE_COUNT) return false
        if (queuedIndexes.get(index)) return true
        if (totalSizeLocked() >= maximumPending) return false
        if (ready.size < readyCapacity) ready.addLast(index) else deferred.addLast(index)
        queuedIndexes.set(index)
        return true
    }

    @Synchronized
    fun offerFirst(index: Int): Boolean {
        if (index !in 0 until DubbingTimeline.MAX_INPUT_CUE_COUNT) return false
        if (queuedIndexes.get(index)) return true
        if (totalSizeLocked() >= maximumPending) return false
        if (ready.size < readyCapacity) {
            ready.addFirst(index)
        } else {
            // Keep the newest urgent cue first without losing the item displaced from the ready
            // window. It is moved to the front of the deferred backlog, preserving FIFO order.
            deferred.addFirst(ready.removeLast())
            ready.addFirst(index)
        }
        queuedIndexes.set(index)
        return true
    }

    @Synchronized
    fun pollFirst(): Int? {
        refillReadyLocked()
        val value = ready.pollFirst() ?: return null
        queuedIndexes.clear(value)
        refillReadyLocked()
        return value
    }

    /** Drain in playback priority order, ready items first followed by the deferred FIFO. */
    @Synchronized
    fun drainTo(destination: MutableCollection<in Int>): Int {
        var drained = 0
        while (ready.isNotEmpty()) {
            destination.add(ready.removeFirst())
            drained++
        }
        while (deferred.isNotEmpty()) {
            destination.add(deferred.removeFirst())
            drained++
        }
        queuedIndexes.clear()
        return drained
    }

    @Synchronized
    fun clear() {
        ready.clear()
        deferred.clear()
        queuedIndexes.clear()
    }

    @Synchronized
    fun contains(index: Int): Boolean =
        index in 0 until DubbingTimeline.MAX_INPUT_CUE_COUNT && queuedIndexes.get(index)

    @Synchronized
    fun isNotEmpty(): Boolean = ready.isNotEmpty() || deferred.isNotEmpty()

    @Synchronized
    fun isEmpty(): Boolean = ready.isEmpty() && deferred.isEmpty()

    @Synchronized
    fun readySize(): Int = ready.size

    @Synchronized
    fun totalSize(): Int = totalSizeLocked()

    private fun refillReadyLocked() {
        while (ready.size < readyCapacity && deferred.isNotEmpty()) {
            ready.addLast(deferred.removeFirst())
        }
    }

    private fun totalSizeLocked(): Int = ready.size + deferred.size

    companion object {
        const val DEFAULT_READY_CAPACITY = 32
    }
}
