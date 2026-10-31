package io.translatetube.local.player

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks per-cue output readiness independently of the contiguous pre-synthesis progress.
 * A later cue can be ready after a seek even while an earlier cue is still synthesizing.
 */
internal class DubbingReadiness {
    // true = synthesized audio is ready; false = intentionally silent/fallback to source audio.
    private val states = ConcurrentHashMap<Int, Boolean>()
    // Maintain the audible-row total incrementally. Recounting every value after each cue
    // mutation turns a long subtitle run into repeated full-map scans (quadratic total work).
    private val audioReadyCueCount = AtomicInteger(0)

    @Synchronized
    fun clear() {
        states.clear()
        audioReadyCueCount.set(0)
    }

    @Synchronized
    fun markPrepared(index: Int, hasAudio: Boolean) {
        if (index < 0) return
        val previous = states.put(index, hasAudio)
        when {
            hasAudio && previous != true -> audioReadyCueCount.incrementAndGet()
            !hasAudio && previous == true -> audioReadyCueCount.decrementAndGet()
        }
    }

    /** Reopen a cue after a previously valid cache entry disappears or fails validation. */
    @Synchronized
    fun markPending(index: Int) {
        if (index < 0) return
        if (states.remove(index) == true) audioReadyCueCount.decrementAndGet()
    }

    /** Number of ready cues, including out-of-order cues prioritized after a seek. */
    fun readyCount(): Int = states.size
    /** O(1) count of ready cues with usable audio; silent/source-fallback rows are excluded. */
    fun audioReadyCount(): Int = audioReadyCueCount.get()

    fun isReady(index: Int): Boolean = index >= 0 && states.containsKey(index)

    fun isPrepared(index: Int): Boolean = index >= 0 && states[index] == true

    fun isSilent(index: Int): Boolean = index >= 0 && states[index] == false
}

/**
 * Maps cache readiness invalidation to the whole merged speech chunk.
 * chunkRedirect stores secondary index -> first index.
 */
internal object DubbingChunkReadinessPolicy {
    fun affectedIndexes(index: Int, chunkRedirect: Map<Int, Int>): List<Int> {
        if (index < 0) return emptyList()
        val firstIndex = chunkRedirect[index] ?: index
        val affected = linkedSetOf(firstIndex)
        chunkRedirect.forEach { (secondaryIndex, ownerIndex) ->
            if (ownerIndex == firstIndex) affected.add(secondaryIndex)
        }
        return affected.toList()
    }
}

internal data class DubbingReadinessProgressSnapshot(
    val preparedUntil: Int,
    val preparedCount: Int,
)

/**
 * Serializes readiness mutations with publication of the contiguous-ready prefix.
 *
 * The callback runs inside the same critical section as each mutation. Without that guarantee,
 * concurrent cache validation and synthesis could publish an older prefix after a newer pending
 * state had already shortened it.
 */
internal class DubbingReadinessProgress(
    private val readiness: DubbingReadiness,
) {
    private val lock = Any()
    private var preparedUntil = 0

    fun clear(
        totalLines: Int,
        publish: (DubbingReadinessProgressSnapshot) -> Unit = {},
    ): DubbingReadinessProgressSnapshot = synchronized(lock) {
        readiness.clear()
        preparedUntil = 0
        snapshot(totalLines).also(publish)
    }

    fun markPrepared(
        index: Int,
        hasAudio: Boolean,
        totalLines: Int,
        publish: (DubbingReadinessProgressSnapshot) -> Unit = {},
    ): DubbingReadinessProgressSnapshot = synchronized(lock) {
        val limit = totalLines.coerceAtLeast(0)
        if (index !in 0 until limit) {
            // Late callbacks from an old/shorter subtitle list must not inflate ready progress.
            readiness.markPending(index)
            snapshot(limit).also(publish)
        } else {
            readiness.markPrepared(index, hasAudio)
            refreshLocked(limit).also(publish)
        }
    }

    fun markPending(
        index: Int,
        totalLines: Int,
        publish: (DubbingReadinessProgressSnapshot) -> Unit = {},
    ): DubbingReadinessProgressSnapshot =
        markPendingGroup(listOf(index), totalLines, publish)

    /** Invalidate a merged cue atomically so no secondary row remains falsely ready. */
    fun markPendingGroup(
        indexes: Iterable<Int>,
        totalLines: Int,
        publish: (DubbingReadinessProgressSnapshot) -> Unit = {},
    ): DubbingReadinessProgressSnapshot = synchronized(lock) {
        val limit = totalLines.coerceAtLeast(0)
        var earliestInvalidated = limit
        indexes.toSet().forEach { index ->
            readiness.markPending(index)
            if (index in 0 until limit && index < earliestInvalidated) {
                earliestInvalidated = index
            }
        }
        if (earliestInvalidated < preparedUntil) preparedUntil = earliestInvalidated
        snapshot(limit).also(publish)
    }

    fun refresh(
        totalLines: Int,
        publish: (DubbingReadinessProgressSnapshot) -> Unit = {},
    ): DubbingReadinessProgressSnapshot = synchronized(lock) {
        refreshLocked(totalLines).also(publish)
    }

    private fun refreshLocked(totalLines: Int): DubbingReadinessProgressSnapshot {
        val limit = totalLines.coerceAtLeast(0)
        var next = preparedUntil.coerceIn(0, limit)
        while (next < limit && readiness.isReady(next)) next++
        preparedUntil = next
        return snapshot(limit)
    }

    private fun snapshot(totalLines: Int): DubbingReadinessProgressSnapshot =
        DubbingReadinessProgressSnapshot(
            preparedUntil = preparedUntil.coerceIn(0, totalLines.coerceAtLeast(0)),
            preparedCount = readiness.readyCount().coerceIn(0, totalLines.coerceAtLeast(0)),
        )
}
