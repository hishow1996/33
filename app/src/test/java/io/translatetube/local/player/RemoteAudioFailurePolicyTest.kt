package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RemoteAudioFailurePolicyTest {
    @Test fun categorizesExpiredLinks() {
        assertEquals("expired_or_missing", RemoteAudioFailurePolicy.classify(
            IllegalStateException("媒体地址不存在或已过期（HTTP 404）")).code)
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
}
