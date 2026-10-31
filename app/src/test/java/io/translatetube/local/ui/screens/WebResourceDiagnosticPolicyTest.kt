package io.translatetube.local.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebResourceDiagnosticPolicyTest {
    @Test
    fun candidateIdentityIgnoresRotatingSignedQueryValues() {
        val first = WebResourceDiagnosticPolicy.candidateIdentity(
            "https://CDN.example/media/video.mp4?token=old&expires=100&quality=720"
        )
        val refreshed = WebResourceDiagnosticPolicy.candidateIdentity(
            "https://cdn.example/media/video.mp4?token=new&expires=200&quality=1080"
        )

        assertEquals(first, refreshed)
        assertFalse(first.contains("old"))
        assertFalse(first.contains("new"))
        assertFalse(first.contains("100"))
        assertFalse(first.contains("200"))
    }

    @Test
    fun candidateIdentitySeparatesDifferentPathsAndQueryKeySets() {
        val base = WebResourceDiagnosticPolicy.candidateIdentity(
            "https://cdn.example/media/video.mp4?token=secret"
        )
        val otherPath = WebResourceDiagnosticPolicy.candidateIdentity(
            "https://cdn.example/media/audio.mp3?token=secret"
        )
        val otherQueryKey = WebResourceDiagnosticPolicy.candidateIdentity(
            "https://cdn.example/media/video.mp4?signature=secret"
        )

        assertFalse(base == otherPath)
        assertFalse(base == otherQueryKey)
    }

    @Test
    fun unknownErrFailedIsNotMisclassifiedAsDnsOrTls() {
        val result = WebResourceDiagnosticPolicy.classifyFailure(
            errorCode = -1,
            description = "net::ERR_FAILED",
        )

        assertEquals("ERROR_UNKNOWN", result.errorCodeName)
        assertEquals("通用失败（Chromium ERR_FAILED）", result.category)
        assertTrue(result.explanation.contains("不能区分 DNS、TCP、TLS"))
    }

    @Test
    fun explicitWebViewCodesRemainSpecific() {
        assertEquals(
            "DNS/域名解析失败",
            WebResourceDiagnosticPolicy.classifyFailure(-2, "").category,
        )
        assertEquals(
            "TLS/证书握手失败",
            WebResourceDiagnosticPolicy.classifyFailure(-11, "").category,
        )
        assertEquals(
            "网络请求超时",
            WebResourceDiagnosticPolicy.classifyFailure(-8, "").category,
        )
        assertEquals(
            "连接媒体服务器失败",
            WebResourceDiagnosticPolicy.classifyFailure(-6, "").category,
        )
    }

    @Test
    fun rangeHeaderIsSummarizedWithoutPersistingHeaderValues() {
        assertEquals(
            "固定字节区间",
            WebResourceDiagnosticPolicy.describeRange("bytes=0-0"),
        )
        assertEquals(
            "从指定偏移到末尾",
            WebResourceDiagnosticPolicy.describeRange("bytes=1024-"),
        )
        assertEquals(
            "文件尾部区间",
            WebResourceDiagnosticPolicy.describeRange("bytes=-500"),
        )
        assertEquals("未提供", WebResourceDiagnosticPolicy.describeRange(null))
    }

    @Test
    fun onlyTransientNetworkFailuresAreAutoResolvedAfterVerification() {
        assertTrue(-1 in WebResourceDiagnosticPolicy.RECOVERABLE_ERROR_CODES)
        assertTrue(-2 in WebResourceDiagnosticPolicy.RECOVERABLE_ERROR_CODES)
        assertTrue(-8 in WebResourceDiagnosticPolicy.RECOVERABLE_ERROR_CODES)
        assertFalse(-12 in WebResourceDiagnosticPolicy.RECOVERABLE_ERROR_CODES)
    }
}
