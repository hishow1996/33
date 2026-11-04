package io.translatetube.local.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDownloadResponsePolicyTest {
    @Test
    fun acceptsOnlyFullHttp200ForUnrangedGets() {
        assertTrue(HttpDownloadResponsePolicy.isCompleteGetResponse(200))
        assertFalse(HttpDownloadResponsePolicy.isCompleteGetResponse(206))
        assertFalse(HttpDownloadResponsePolicy.isCompleteGetResponse(204))
        assertFalse(HttpDownloadResponsePolicy.isCompleteGetResponse(302))
        assertFalse(HttpDownloadResponsePolicy.isCompleteGetResponse(404))
    }

    @Test
    fun acceptsOnlyUnknownOrPositiveBoundedContentLengths() {
        assertTrue(HttpDownloadResponsePolicy.isValidExpectedLength(-1L, 1024L))
        assertTrue(HttpDownloadResponsePolicy.isValidExpectedLength(1024L, 1024L))
        assertFalse(HttpDownloadResponsePolicy.isValidExpectedLength(-2L, 1024L))
        assertFalse(HttpDownloadResponsePolicy.isValidExpectedLength(0L, 1024L))
        assertFalse(HttpDownloadResponsePolicy.isValidExpectedLength(1025L, 1024L))
        assertFalse(HttpDownloadResponsePolicy.isValidExpectedLength(1024L, 0L))
    }

    @Test
    fun onlyAbandonsASlowPrimarySourceAfterEnoughEvidence() {
        assertTrue(
            HttpDownloadSpeedPolicy.shouldFallbackFromPrimarySource(
                sourceIndex = 0,
                downloadedBytes = 1L * 1024L * 1024L,
                elapsedMillis = 10_000L,
            ),
        )
        assertFalse(
            HttpDownloadSpeedPolicy.shouldFallbackFromPrimarySource(
                sourceIndex = 0,
                downloadedBytes = 512L * 1024L,
                elapsedMillis = 10_000L,
            ),
        )
        assertFalse(
            HttpDownloadSpeedPolicy.shouldFallbackFromPrimarySource(
                sourceIndex = 0,
                downloadedBytes = 8L * 1024L * 1024L,
                elapsedMillis = 10_000L,
            ),
        )
        assertFalse(
            HttpDownloadSpeedPolicy.shouldFallbackFromPrimarySource(
                sourceIndex = 1,
                downloadedBytes = 1L * 1024L * 1024L,
                elapsedMillis = 60_000L,
            ),
        )
        assertFalse(
            HttpDownloadSpeedPolicy.shouldFallbackFromPrimarySource(
                sourceIndex = 0,
                downloadedBytes = 2L * 1024L * 1024L,
                elapsedMillis = 7_999L,
            ),
        )
    }

    @Test
    fun checksActualPayloadLengthAndRejectsEmptyOrOversizedDownloads() {
        assertTrue(HttpDownloadResponsePolicy.isCompletePayload(1024L, 1024L, 2048L))
        assertTrue(HttpDownloadResponsePolicy.isCompletePayload(-1L, 512L, 2048L))
        assertFalse(HttpDownloadResponsePolicy.isCompletePayload(1024L, 1023L, 2048L))
        assertFalse(HttpDownloadResponsePolicy.isCompletePayload(1024L, 1025L, 2048L))
        assertFalse(HttpDownloadResponsePolicy.isCompletePayload(0L, 1024L, 2048L))
        assertFalse(HttpDownloadResponsePolicy.isCompletePayload(-2L, 1024L, 2048L))
        assertFalse(HttpDownloadResponsePolicy.isCompletePayload(-1L, 0L, 2048L))
        assertFalse(HttpDownloadResponsePolicy.isCompletePayload(-1L, 2049L, 2048L))
    }
}