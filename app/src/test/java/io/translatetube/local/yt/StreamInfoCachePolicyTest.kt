package io.translatetube.local.yt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamInfoCachePolicyTest {
    @Test
    fun acceptsFreshMetadataAndExpiresSignedUrlsAfterFiveMinutes() {
        val now = 20_000_000L
        assertTrue(StreamInfoCachePolicy.isFresh(now, now))
        assertTrue(StreamInfoCachePolicy.isFresh(now, now + StreamInfoCachePolicy.TTL_MS))
        assertFalse(StreamInfoCachePolicy.isFresh(now, now + StreamInfoCachePolicy.TTL_MS + 1L))
    }

    @Test
    fun rejectsMissingFutureDatedAndExpiredTimestamps() {
        val now = 20_000_000L
        assertFalse(StreamInfoCachePolicy.isFresh(0L, now))
        assertFalse(StreamInfoCachePolicy.isFresh(now + 1L, now))
        assertFalse(StreamInfoCachePolicy.isFresh(now - StreamInfoCachePolicy.TTL_MS - 1L, now))
    }

    @Test
    fun onlyLatestRequestInCurrentInvalidationGenerationCanPublish() {
        assertTrue(StreamInfoCachePolicy.mayPublish(7, 7, 2, 2))
        assertFalse(StreamInfoCachePolicy.mayPublish(6, 7, 2, 2))
        assertFalse(StreamInfoCachePolicy.mayPublish(7, 7, 1, 2))
    }
    @Test
    fun retriesOnlyTransportErrorsAndWrappedTransportCauses() {
        assertTrue(StreamInfoCachePolicy.isRetryableFailure(java.io.IOException("timeout")))
        assertTrue(
            StreamInfoCachePolicy.isRetryableFailure(
                IllegalStateException("extractor failed", java.net.SocketTimeoutException("read timeout"))
            )
        )
        assertFalse(StreamInfoCachePolicy.isRetryableFailure(IllegalArgumentException("invalid video URL")))
        assertFalse(StreamInfoCachePolicy.isRetryableFailure(java.util.concurrent.CancellationException("cancelled")))
    }

}
