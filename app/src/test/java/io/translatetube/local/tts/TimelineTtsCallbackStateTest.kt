package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTtsCallbackStateTest {
    @Test
    fun startCallbackCanOnlyBeginOnce() {
        val state = TimelineTtsCallbackState()

        assertTrue(state.beginStart())
        assertFalse(state.beginStart())

        val finished = state.finishStart(null)
        assertFalse(finished.completionReady)
    }

    @Test
    fun completionBeforeStartIsReportedAsAnOutputUnconfirmedFailure() {
        val state = TimelineTtsCallbackState()

        val completion = state.requestFinish(null)

        assertTrue(completion.accepted)
        assertTrue(completion.completionReady)
        assertEquals(
            "系统 TTS 完成回调先于开始回调，无法确认朗读链路是否启动",
            completion.error,
        )
        assertFalse(state.beginStart())
        assertFalse(state.requestFinish("duplicate").accepted)
    }

    @Test
    fun completionWaitsUntilAnInFlightStartCallbackReturns() {
        val state = TimelineTtsCallbackState()
        assertTrue(state.beginStart())

        val done = state.requestFinish(null)
        assertTrue(done.accepted)
        assertFalse(done.completionReady)

        val afterStart = state.finishStart(null)
        assertTrue(afterStart.accepted)
        assertTrue(afterStart.completionReady)
        assertEquals(null, afterStart.error)
    }

    @Test
    fun startCallbackExceptionTurnsTheUtteranceIntoOneFailure() {
        val state = TimelineTtsCallbackState()
        assertTrue(state.beginStart())

        val result = state.finishStart(IllegalStateException("callback exploded"))

        assertTrue(result.completionReady)
        assertTrue(result.error.orEmpty().contains("callback exploded"))
        assertFalse(state.requestFinish("second terminal event").accepted)
    }

    @Test
    fun intentionalCancellationAlsoSuppressesALateStartCallbackException() {
        val state = TimelineTtsCallbackState()
        assertTrue(state.beginStart())
        state.cancel()

        val lateStartReturn = state.finishStart(IllegalStateException("late callback error"))

        assertFalse(lateStartReturn.accepted)
        assertFalse(lateStartReturn.completionReady)
        assertEquals(null, lateStartReturn.error)
    }

    @Test
    fun intentionalCancellationDoesNotDeliverACompletionCallback() {
        val state = TimelineTtsCallbackState()
        assertTrue(state.beginStart())
        state.cancel()

        val lateStartReturn = state.finishStart(null)

        assertFalse(lateStartReturn.accepted)
        assertFalse(lateStartReturn.completionReady)
        assertFalse(state.requestFinish(null).accepted)
    }
}
