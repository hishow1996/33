package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class RemoteAudioFailurePolicyTest {
    @Test fun categorizesDnsFailuresSeparatelyFromConnections() {
        val diagnosis = RemoteAudioFailurePolicy.classify(
            UnknownHostException("Unable to resolve host")
        )
        assertEquals("dns_resolution_failed", diagnosis.code)
        assertEquals("DNS 解析失败", diagnosis.label)
    }

    @Test fun categorizesConnectionAndTlsFailuresSeparately() {
        assertEquals("network_connection", RemoteAudioFailurePolicy.classify(
            ConnectException("connection refused")
        ).code)
        assertEquals("tls_handshake_failed", RemoteAudioFailurePolicy.classify(
            SSLException("handshake failed")
        ).code)
    }

    @Test fun categorizesExpiredLinks() {
        assertEquals("expired_or_missing", RemoteAudioFailurePolicy.classify(
            IllegalStateException("媒体地址不存在或已过期（HTTP 404）")).code)
    }

    @Test fun categorizesGoneResponsesAsExpiredLinks() {
        assertEquals("expired_or_missing", RemoteAudioFailurePolicy.classify(
            IllegalStateException("HLS 音轨分段请求失败（HTTP 410）")
        ).code)
    }

    @Test fun categorizesForbiddenResponses() {
        assertEquals("access_denied", RemoteAudioFailurePolicy.classify(
            IOException("媒体文件未返回完整响应（HTTP 403）")).code)
    }

    @Test fun distinguishesHtmlOrJsonErrorPages() {
        assertEquals("not_media_payload", RemoteAudioFailurePolicy.classify(
            IllegalStateException("服务器返回了网页或 JSON，而不是可读取的音频")).code)
    }

    @Test fun categorizesMissingAudioTracks() {
        assertEquals("no_audio_track", RemoteAudioFailurePolicy.classify(
            IllegalStateException("该视频流中没有可提取的音频轨")).code)
    }

    @Test fun categorizesUnsupportedPackaging() {
        assertEquals("unsupported_audio_format", RemoteAudioFailurePolicy.classify(
            IllegalStateException("当前设备无法安全封装网页音频编码（audio/mpeg）")).code)
    }

    @Test fun categorizesTimeoutsBeforeGenericIoFailures() {
        assertEquals("network_timeout", RemoteAudioFailurePolicy.classify(
            SocketTimeoutException("read timed out")).code)
    }
    @Test fun permitsOneFreshPageExtractionForExpiredAndForbiddenMediaUrls() {
        assertEquals(true, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(
            IOException("HLS segment request failed (HTTP 403)")
        ))
        assertEquals(true, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(
            IOException("HLS segment request failed (HTTP 404)")
        ))
        assertEquals(true, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(
            IOException("HLS segment request failed (HTTP 410)")
        ))
    }

    @Test fun doesNotRefreshPageForRateLimitsOrNetworkFailures() {
        assertEquals(false, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(
            IOException("HTTP 429 Too Many Requests")
        ))
        assertEquals(false, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(
            UnknownHostException("Unable to resolve host")
        ))
    }

    @Test fun deepestHttpStatusOverridesStaleOuterWrapperStatus() {
        val outerForbidden = IOException(
            "wrapper says HTTP 403",
            IOException("HLS segment request failed; HTTP 429 Too Many Requests"),
        )
        assertEquals("rate_limited", RemoteAudioFailurePolicy.classify(outerForbidden).code)
        assertEquals(false, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(outerForbidden))

        val outerMissing = IOException(
            "wrapper says HTTP 404",
            IOException("media endpoint returned HTTP 503 Service Unavailable"),
        )
        assertEquals("server_error", RemoteAudioFailurePolicy.classify(outerMissing).code)
        assertEquals(false, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(outerMissing))
    }

    @Test fun cyclicCauseChainIsBoundedDuringClassification() {
        val first = IOException("wrapper HTTP 403")
        val second = IllegalStateException("inner HTTP 429")
        first.initCause(second)
        second.initCause(first)
        assertEquals("rate_limited", RemoteAudioFailurePolicy.classify(first).code)
        assertEquals(false, RemoteAudioFailurePolicy.shouldRetryWithFreshPageExtraction(first))
    }

}
