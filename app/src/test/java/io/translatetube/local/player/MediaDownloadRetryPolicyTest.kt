package io.translatetube.local.player

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadRetryPolicyTest {
    @Test
    fun retriesTransientNetworkErrorsAndHttpStatuses() {
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IOException("connection reset")))
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IOException("HTTP 429: rate limited")))
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IOException("HTTP 503: service unavailable")))
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载失败 503")))
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("HLS 片段下载失败（HTTP 429）。")))
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载不完整：响应声明 1024 字节，实际收到 512 字节")))
        // Unknown Content-Length can still be truncated relative to the HLS range declaration.
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("HLS BYTERANGE 片段不完整：清单要求 512 字节，实际收到 300 字节")))
    }

    @Test
    fun refreshesStaleSignedStreamsButNotTransientServerFailures() {
        assertTrue(MediaDownloadRetryPolicy.shouldRefreshSignedStream(IllegalStateException("音频下载失败 403")))
        assertTrue(MediaDownloadRetryPolicy.shouldRefreshSignedStream(IllegalStateException("HLS 清单读取失败（HTTP 410）。")))
        assertTrue(MediaDownloadRetryPolicy.shouldRefreshSignedStream(IllegalStateException("链接可能过期或需要登录")))
        assertFalse(MediaDownloadRetryPolicy.shouldRefreshSignedStream(IllegalStateException("音频下载失败 503")))
        assertFalse(MediaDownloadRetryPolicy.shouldRefreshSignedStream(IllegalStateException("本地音频格式不受支持")))
        assertFalse(MediaDownloadRetryPolicy.shouldRefreshSignedStream(CancellationException("task cancelled")))
    }

    @Test
    fun signedStreamRefreshDetectionIsIndependentOfDeviceLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertTrue(
                MediaDownloadRetryPolicy.shouldRefreshSignedStream(
                    IllegalStateException("SIGNATURE HAS EXPIRED"),
                ),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun wrappedCancellationIsNeverRetriedOrRefreshed() {
        val wrapped = IOException("download wrapper", CancellationException("obsolete task"))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(wrapped))
        assertFalse(MediaDownloadRetryPolicy.shouldRefreshSignedStream(wrapped))
    }

    @Test
    fun ytDlpFallbackIsSuppressedForCancellationAndInactiveSessions() {
        assertTrue(MediaDownloadRetryPolicy.shouldFallbackToStreaming(IOException("format unsupported"), true))
        assertFalse(MediaDownloadRetryPolicy.shouldFallbackToStreaming(CancellationException("cancelled"), true))
        assertFalse(
            MediaDownloadRetryPolicy.shouldFallbackToStreaming(
                IOException("wrapped", CancellationException("cancelled")),
                true,
            )
        )
        assertFalse(MediaDownloadRetryPolicy.shouldFallbackToStreaming(IOException("format unsupported"), false))
    }

    @Test
    fun doesNotRetryPermanentErrorsOrCancellation() {
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载失败 403")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IOException("HTTP 401: unauthorized")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IOException("HTTP 403: forbidden")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IOException("HTTP 404: not found")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IOException("HTTP 410: gone")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载失败 404")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("服务器返回了网页或 JSON，而不是可读取的音频")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(CancellationException("task cancelled")))
    }
}
