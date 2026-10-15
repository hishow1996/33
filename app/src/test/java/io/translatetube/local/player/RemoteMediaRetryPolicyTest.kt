package io.translatetube.local.player

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaRetryPolicyTest {
    @Test
    fun refreshesHtmlJsonExpiredAndAuthorizationFailures() {
        assertTrue(RemoteMediaRetryPolicy.shouldRefreshFromPage(
            IllegalStateException("服务器返回了网页或 JSON，而不是可读取的音频")
        ))
        assertTrue(RemoteMediaRetryPolicy.shouldRefreshFromPage(
            IllegalStateException("HTTP 403: access denied")
        ))
        assertTrue(RemoteMediaRetryPolicy.shouldRefreshFromPage(
            IllegalStateException("signature has expired")
        ))
    }

    @Test
    fun doesNotRetryCancellationOrUnrelatedFailures() {
        assertFalse(RemoteMediaRetryPolicy.shouldRefreshFromPage(CancellationException("cancelled")))
        assertFalse(RemoteMediaRetryPolicy.shouldRefreshFromPage(IllegalStateException("磁盘空间不足")))
    }
}
