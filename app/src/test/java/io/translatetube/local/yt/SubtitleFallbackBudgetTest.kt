package io.translatetube.local.yt

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFallbackBudgetTest {
    @Test
    fun stopsAfterTwoConsecutiveConnectivityFailures() {
        val budget = SubtitleFallbackBudget()
        assertFalse(budget.recordTrackFailure(UnknownHostException("www.youtube.com")))
        assertTrue(budget.recordTrackFailure(SocketTimeoutException("timeout")))
    }

    @Test
    fun aSingleNetworkBlipDoesNotEndTheSearch() {
        val budget = SubtitleFallbackBudget()
        assertFalse(budget.recordTrackFailure(SocketTimeoutException("timeout")))
        // The next track answered (just without usable cues): connectivity is fine again.
        assertFalse(budget.recordTrackFailure(null))
        assertFalse(budget.recordTrackFailure(SocketTimeoutException("timeout")))
    }

    @Test
    fun rateLimitStopsImmediately() {
        val budget = SubtitleFallbackBudget()
        assertTrue(budget.recordTrackFailure(SubtitleHttpException(429)))
    }

    @Test
    fun tracksWithoutUsableCuesNeverEndTheSearch() {
        val budget = SubtitleFallbackBudget()
        repeat(50) { assertFalse(budget.recordTrackFailure(null)) }
        repeat(50) { assertFalse(budget.recordTrackFailure(SubtitleHttpException(404))) }
        assertFalse(SubtitleFallbackBudget.isRateLimited(SubtitleHttpException(404)))
        assertFalse(SubtitleFallbackBudget.isConnectivityFailure(SubtitleHttpException(404)))
    }

    @Test
    fun recognisesCutConnectionsReportedAsPlainIoException() {
        assertTrue(SubtitleFallbackBudget.isConnectivityFailure(IOException("unexpected end of stream on https://x")))
        assertTrue(SubtitleFallbackBudget.isConnectivityFailure(IOException("Connection reset")))
        assertFalse(SubtitleFallbackBudget.isConnectivityFailure(IOException("disk full")))
    }

    @Test
    fun subtitleHttpExceptionKeepsOriginalMessage() {
        assertTrue(SubtitleHttpException(403).message!!.contains("HTTP 403"))
    }
}
