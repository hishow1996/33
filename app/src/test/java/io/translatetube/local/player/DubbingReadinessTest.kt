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
    fun reattachClearsAllReadinessFromPreviousSubtitleSession() {
        val readiness = DubbingReadiness()
        readiness.markPrepared(0, hasAudio = true)
        readiness.markPrepared(1, hasAudio = false)

        readiness.clear()

        assertFalse(readiness.isReady(0))
        assertFalse(readiness.isReady(1))
        assertFalse(readiness.isSilent(1))
    }
}
