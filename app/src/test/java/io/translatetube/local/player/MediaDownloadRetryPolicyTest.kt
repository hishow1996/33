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
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载失败 503")))
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("HLS 片段下载失败（HTTP 429）。")))
        assertTrue(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载不完整：响应声明 1024 字节，实际收到 512 字节")))
    }

    @Test
    fun doesNotRetryPermanentErrorsOrCancellation() {
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载失败 403")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("音频下载失败 404")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(IllegalStateException("服务器返回了网页或 JSON，而不是可读取的音频")))
        assertFalse(MediaDownloadRetryPolicy.shouldRetry(CancellationException("task cancelled")))
    }
}
