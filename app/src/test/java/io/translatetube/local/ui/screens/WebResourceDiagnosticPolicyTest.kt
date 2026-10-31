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

    @Test
    fun requestHeaderSummaryRecordsPresenceAndHostsButNeverValues() {
        val summary = WebResourceDiagnosticPolicy.summarizeRequestHeaders(
            headers = mapOf(
                "Referer" to "https://watch.example/video?id=private",
                "Origin" to "https://watch.example",
                "Cookie" to "session=do-not-store",
                "Authorization" to "Bearer do-not-store",
                "User-Agent" to "private-agent-string",
                "Range" to "bytes=0-0",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "video",
            ),
            pageUrl = "https://watch.example/video",
        )

        assertEquals("watch.example", summary["Referer 主机名"])
        assertEquals("true", summary["Referer 与页面主机是否一致"])
        assertEquals("watch.example", summary["Origin 主机名"])
        assertEquals("true", summary["Origin 与页面主机是否一致"])
        assertEquals("true", summary["请求头集合是否显式包含 Cookie"])
        assertEquals("true", summary["请求头集合是否显式包含 Authorization"])
        assertEquals("固定字节区间", summary["Range 类型"])
        assertTrue(!summary.values.any { it.contains("do-not-store") })
        assertTrue(!summary.values.any { it.contains("private-agent-string") })
        assertTrue(!summary.values.any { it.contains("private") })
    }

    @Test
    fun responseHeaderSummaryUsesAllowlistedSafeValuesOnly() {
        val summary = WebResourceDiagnosticPolicy.summarizeResponseHeaders(
            requestUrl = "https://media.example/master.m3u8",
            headers = mapOf(
                "Content-Length" to "1024",
                "Content-Range" to "bytes 0-0/1024",
                "Accept-Ranges" to "bytes",
                "Server" to "cloudflare nginx/1.20 secret-server-version",
                "Cache-Control" to "private, max-age=3600, no-store, custom-secret=abc",
                "Location" to "https://login.example/authorize?token=secret",
                "WWW-Authenticate" to "Bearer private-token",
                "Access-Control-Allow-Origin" to "https://watch.example",
                "Access-Control-Allow-Credentials" to "true",
                "Vary" to "Origin, Accept-Encoding, X-Private",
                "ETag" to "secret-etag",
            ),
        )

        assertEquals("1024", summary["Content-Length（字节）"])
        assertEquals("bytes 0-0/1024", summary["Content-Range"])
        assertEquals("cloudflare, nginx", summary["Server 标识"])
        assertTrue(summary["Cache-Control 策略"].orEmpty().contains("max-age=3600"))
        assertFalse(summary["Cache-Control 策略"].orEmpty().contains("custom-secret"))
        assertEquals("login.example", summary["Location 目标主机名"])
        assertEquals("Bearer", summary["WWW-Authenticate 认证方案"])
        assertEquals("指定来源（watch.example）", summary["Access-Control-Allow-Origin"])
        assertEquals("true", summary["Access-Control-Allow-Credentials"])
        assertEquals("origin, accept-encoding", summary["Vary"])
        assertTrue(!summary.values.any { it.contains("secret") })
    }

    @Test
    fun blankHttpReasonPhraseGetsAnInformativeHttp2SafeFallback() {
        assertTrue(
            WebResourceDiagnosticPolicy.describeHttpReason(403, "").contains("HTTP/2")
        )
        assertTrue(
            WebResourceDiagnosticPolicy.explainHttpFailure(403).contains("不能确定具体原因")
        )
    }
