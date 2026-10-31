package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingReadinessTest {
    @Test
    fun laterCueCanPlayBeforeEarlierCuesFinishSynthesizing() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(8, hasAudio = true)

        assertTrue(readiness.isReady(8))
        assertTrue(readiness.isPrepared(8))
        assertFalse(readiness.isReady(0))
        assertFalse(readiness.isPrepared(0))
    }

    @Test
    fun successfulEmptyMarkerIsReadyButMustFallBackToOriginalAudio() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(3, hasAudio = false)

        assertTrue(readiness.isReady(3))
        assertFalse(readiness.isPrepared(3))
        assertTrue(readiness.isSilent(3))
    }

    @Test
    fun invalidatedCacheReturnsCueToPendingInsteadOfReportingPermanentSilence() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(2, hasAudio = false)

        readiness.markPending(2)

        assertFalse(readiness.isReady(2))
        assertFalse(readiness.isPrepared(2))
        assertFalse(readiness.isSilent(2))

        readiness.markPrepared(2, hasAudio = true)
        assertTrue(readiness.isPrepared(2))
        readiness.markPending(2)
        assertFalse(readiness.isReady(2))
    }

    @Test
    fun repeatedAudioSilentAndPendingTransitionsReplaceOneState() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(6, hasAudio = false)
        assertEquals(1, readiness.readyCount())
        assertTrue(readiness.isSilent(6))
        assertFalse(readiness.isPrepared(6))

        readiness.markPrepared(6, hasAudio = true)
        assertEquals(1, readiness.readyCount())
        assertTrue(readiness.isPrepared(6))
        assertFalse(readiness.isSilent(6))

        readiness.markPending(6)
        assertEquals(0, readiness.readyCount())
        assertFalse(readiness.isReady(6))

        readiness.markPrepared(6, hasAudio = false)
        assertEquals(1, readiness.readyCount())
        assertTrue(readiness.isSilent(6))
        readiness.clear()
        assertEquals(0, readiness.readyCount())
    }

    @Test
    fun audioReadyCountExcludesSilentRowsAndTracksRetries() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(0, hasAudio = true)
        readiness.markPrepared(1, hasAudio = false)
        readiness.markPrepared(2, hasAudio = true)

        assertEquals(3, readiness.readyCount())
        assertEquals(2, readiness.audioReadyCount())

        // Repeating the same result must not double-count an already audible cue.
        readiness.markPrepared(0, hasAudio = true)
        assertEquals(2, readiness.audioReadyCount())

        // Replacing audible output with a silent fallback decrements the total exactly once.
        readiness.markPrepared(0, hasAudio = false)
        assertEquals(1, readiness.audioReadyCount())
        readiness.markPrepared(0, hasAudio = false)
        assertEquals(1, readiness.audioReadyCount())

        readiness.markPending(2)
        assertEquals(0, readiness.audioReadyCount())

        // A retry that later succeeds adds the cue back to the count.
        readiness.markPrepared(1, hasAudio = true)
        assertEquals(1, readiness.audioReadyCount())

        readiness.clear()
        assertEquals(0, readiness.audioReadyCount())
    }

    @Test
    fun readyCountTracksOutOfOrderCuesAndCacheInvalidation() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(8, hasAudio = true)
        readiness.markPrepared(2, hasAudio = false)

        assertEquals(2, readiness.readyCount())

        readiness.markPending(8)
        assertEquals(1, readiness.readyCount())
        assertTrue(readiness.isSilent(2))

        readiness.markPending(2)
        assertEquals(0, readiness.readyCount())
    }

    @Test
    fun mergedChunkInvalidationMarksFirstAndAllSecondaryRowsPendingAtomically() {
        val readiness = DubbingReadiness()
        val progress = DubbingReadinessProgress(readiness)
        progress.clear(6)
        repeat(6) { progress.markPrepared(it, hasAudio = true, totalLines = 6) }

        val redirects = linkedMapOf(3 to 2, 4 to 2, 5 to 1)
        val affected = DubbingChunkReadinessPolicy.affectedIndexes(4, redirects)
        assertEquals(listOf(2, 3, 4), affected)

        val snapshot = progress.markPendingGroup(affected, 6)
        assertEquals(DubbingReadinessProgressSnapshot(2, 3), snapshot)
        assertFalse(readiness.isReady(2))
        assertFalse(readiness.isReady(3))
        assertFalse(readiness.isReady(4))
        assertTrue(readiness.isPrepared(1))
        assertTrue(readiness.isPrepared(5))
    }

    @Test
    fun invalidatingAnySecondaryRowResolvesItsWholeChunk() {
        val redirects = linkedMapOf(3 to 2, 4 to 2, 7 to 6)
        assertEquals(listOf(2, 3, 4), DubbingChunkReadinessPolicy.affectedIndexes(3, redirects))
        assertEquals(listOf(6, 7), DubbingChunkReadinessPolicy.affectedIndexes(7, redirects))
        assertEquals(listOf(5), DubbingChunkReadinessPolicy.affectedIndexes(5, redirects))
        assertEquals(emptyList<Int>(), DubbingChunkReadinessPolicy.affectedIndexes(-1, redirects))
    }

    @Test
    fun reattachClearsAllReadinessFromPreviousSubtitleSession() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(0, hasAudio = true)
        readiness.markPrepared(1, hasAudio = false)

        readiness.clear()

        assertFalse(readiness.isReady(0))
        assertFalse(readiness.isReady(1))
        assertFalse(readiness.isSilent(1))
    }

    @Test
    fun progressPrefixStopsAtFirstPendingCueAndResumesWhenItBecomesReady() {
        val readiness = DubbingReadiness()
        val progress = DubbingReadinessProgress(readiness)

        progress.markPrepared(0, true, 4)
        progress.markPrepared(1, true, 4)
        progress.markPrepared(2, false, 4)
        assertEquals(DubbingReadinessProgressSnapshot(3, 3), progress.refresh(4))

        assertEquals(DubbingReadinessProgressSnapshot(1, 2), progress.markPending(1, 4))
        assertEquals(DubbingReadinessProgressSnapshot(3, 3), progress.markPrepared(1, true, 4))
        assertEquals(DubbingReadinessProgressSnapshot(3, 3), progress.refresh(4))
    }

    @Test
    fun progressTracksReadyRowsBeyondAGapWithoutInflatingThePrefix() {
        val progress = DubbingReadinessProgress(DubbingReadiness())

        assertEquals(DubbingReadinessProgressSnapshot(0, 0), progress.clear(5))
        assertEquals(DubbingReadinessProgressSnapshot(0, 1), progress.markPrepared(3, true, 5))
        assertEquals(DubbingReadinessProgressSnapshot(0, 2), progress.markPrepared(1, false, 5))
        assertEquals(DubbingReadinessProgressSnapshot(2, 3), progress.markPrepared(0, true, 5))
        assertEquals(DubbingReadinessProgressSnapshot(4, 4), progress.markPrepared(2, true, 5))
    }

    @Test
    fun concurrentReadinessMutationsPublishAConsistentPrefixSnapshot() {
        val readiness = DubbingReadiness()
        val progress = DubbingReadinessProgress(readiness)
        val total = 64
        val startGate = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads = (0 until 4).map { worker ->
            Thread {
                startGate.await()
                repeat(400) { step ->
                    val index = (step * 17 + worker * 11) % total
                    val publish: (DubbingReadinessProgressSnapshot) -> Unit = { snapshot ->
                        var expectedPrefix = 0
                        while (expectedPrefix < total && readiness.isReady(expectedPrefix)) {
                            expectedPrefix++
                        }
                        if (
                            snapshot.preparedUntil != expectedPrefix ||
                            snapshot.preparedCount != readiness.readyCount()
                        ) {
                            errors.add(
                                "published=" + snapshot +
                                    " expectedPrefix=" + expectedPrefix +
                                    " ready=" + readiness.readyCount()
                            )
                        }
                    }
                    if ((step + worker) % 4 == 0) {
                        progress.markPending(index, total, publish)
                    } else {
                        progress.markPrepared(
                            index,
                            hasAudio = step % 2 == 0,
                            totalLines = total,
                            publish = publish,
                        )
                    }
                }
            }.apply { this.start() }
        }
        startGate.countDown()
        threads.forEach { it.join() }

        assertTrue("inconsistent readiness snapshots: " + errors, errors.isEmpty())
    }


@Test
    fun outOfRangePreparedCallbacksDoNotInflateProgress() {
        val progress = DubbingReadinessProgress(DubbingReadiness())
        assertEquals(DubbingReadinessProgressSnapshot(0, 0), progress.clear(3))
        assertEquals(DubbingReadinessProgressSnapshot(0, 0), progress.markPrepared(-1, true, 3))
        assertEquals(DubbingReadinessProgressSnapshot(0, 0), progress.markPrepared(3, true, 3))
        assertEquals(DubbingReadinessProgressSnapshot(0, 0), progress.markPrepared(99, false, 3))
    }

    @Test
    fun outOfRangePendingCallbackDoesNotMoveTheValidReadyPrefix() {
        val progress = DubbingReadinessProgress(DubbingReadiness())
        progress.clear(2)
        progress.markPrepared(0, true, 2)
        assertEquals(DubbingReadinessProgressSnapshot(1, 1), progress.markPending(100, 2))
        assertEquals(DubbingReadinessProgressSnapshot(1, 1), progress.markPending(-1, 2))
    }

    @Test
    fun staleCallbacksForThePreviousListCannotMarkNewRowsReady() {
        val progress = DubbingReadinessProgress(DubbingReadiness())
        progress.clear(5)
        progress.markPrepared(4, true, 5)
        progress.clear(2)
        assertEquals(DubbingReadinessProgressSnapshot(0, 0), progress.markPrepared(4, true, 2))
        assertEquals(DubbingReadinessProgressSnapshot(0, 0), progress.refresh(2))
    }

}
