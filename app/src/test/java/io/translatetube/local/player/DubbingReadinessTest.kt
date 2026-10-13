package io.translatetube.local.player

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
