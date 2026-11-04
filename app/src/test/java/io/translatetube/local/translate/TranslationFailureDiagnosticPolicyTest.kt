package io.translatetube.local.translate

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFailureDiagnosticPolicyTest {
    @Test
    fun extractsHttpStatusFromCommonProviderErrorFormats() {
        assertEquals("429", TranslationFailureDiagnosticPolicy.httpStatus(IllegalStateException("HTTP 429 Too Many Requests")))
        assertEquals("401", TranslationFailureDiagnosticPolicy.httpStatus(IllegalStateException("status code: 401")))
        assertEquals("未从异常文本中识别", TranslationFailureDiagnosticPolicy.httpStatus(IllegalStateException("offline")))
    }

    @Test
    fun classifiesNetworkPermissionAndProviderFailures() {
        assertEquals("AUTH_OR_PERMISSION", TranslationFailureDiagnosticPolicy.classify(IllegalStateException("HTTP 403 forbidden")))
        assertEquals("INVALID_REQUEST_OR_PAYLOAD", TranslationFailureDiagnosticPolicy.classify(IllegalStateException("HTTP 422 invalid request")))
        assertEquals("BILLING_OR_QUOTA", TranslationFailureDiagnosticPolicy.classify(IllegalStateException("HTTP 402 payment required")))
        assertEquals("UNSUPPORTED_CONTENT_TYPE", TranslationFailureDiagnosticPolicy.classify(IllegalStateException("HTTP 415 unsupported media type")))
        assertEquals("ENDPOINT_OR_MODEL_NOT_FOUND", TranslationFailureDiagnosticPolicy.classify(IllegalStateException("HTTP 404 model not found")))
        assertEquals("RATE_LIMITED", TranslationFailureDiagnosticPolicy.classify(IllegalStateException("HTTP 429 rate limit")))
        assertEquals("REQUEST_TIMEOUT", TranslationFailureDiagnosticPolicy.classify(java.net.SocketTimeoutException("timed out")))
        assertEquals("DNS_OR_HOST_UNREACHABLE", TranslationFailureDiagnosticPolicy.classify(java.net.UnknownHostException("api.example.test")))
        assertEquals("NETWORK_IO_FAILURE", TranslationFailureDiagnosticPolicy.classify(java.io.IOException("connection reset")))
    }

    @Test
    fun formatsBoundedSubtitleEvidenceWithRowAndTimestamp() {
        val lines = listOf(
            SubLine(0, 100L, 900L, "Hello world, this is a source subtitle."),
            SubLine(1, 1_000L, 1_800L, "Second line"),
        )
        val detail = TranslationFailureDiagnosticPolicy.describeSubtitleRows(
            lines = lines,
            indices = listOf(0, 1),
            translatedByIndex = mapOf(0 to "Hello world."),
            reasonByIndex = mapOf(0 to "SOURCE_ECHO", 1 to "EMPTY"),
            maxRows = 1,
        )
        assertTrue(detail.contains("第 1 行"))
        assertTrue(detail.contains("SOURCE_ECHO"))
        assertTrue(detail.contains("Hello world"))
        assertTrue(detail.contains("另有 1 行未展开"))
    }

    @Test
    fun redactsControlCharactersAndBoundsLongExcerpts() {
        val snippet = TranslationFailureDiagnosticPolicy.safeSnippet("one\n two\u0000 three", 16)
        assertTrue(snippet.length <= 20)
        assertTrue(!snippet.contains("\n"))
    }

    @Test
    fun causeChainKeepsHostAndHttpDetailsButHidesUrlPathsAndCredentials() {
        val failure = IllegalStateException(
            "HTTP 401 at https://api.example.test/v1/chat?api_key=secret-value; Authorization: Bearer super-secret-token",
        )
        val details = TranslationFailureDiagnosticPolicy.causeChain(failure)
        assertTrue(details.contains("api.example.test"))
        assertTrue(details.contains("HTTP 401"))
        assertTrue(!details.contains("secret-value"))
        assertTrue(!details.contains("super-secret-token"))
        assertTrue(!details.contains("/v1/chat"))
        assertTrue(!TranslationFailureDiagnosticPolicy.safeSnippet("Bearer hidden-token").contains("hidden-token"))
        assertTrue(!TranslationFailureDiagnosticPolicy.safeSnippet("api_key=hidden-value").contains("hidden-value"))
        val urlPreview = TranslationFailureDiagnosticPolicy.safeSnippet("https://cdn.example.test/video?id=123&token=signed-secret")
        assertTrue(urlPreview.contains("cdn.example.test"))
        assertTrue(!urlPreview.contains("signed-secret"))
        assertTrue(!urlPreview.contains("/video"))
    }

    @Test
    fun sourceTypesRemainConsistentAcrossPlaybackSources() {
        assertEquals("YouTube", TranslationFailureDiagnosticPolicy.sourceType("youtube:abc"))
        assertEquals("网页视频/远程媒体", TranslationFailureDiagnosticPolicy.sourceType("remote:abc"))
        assertEquals("本地视频", TranslationFailureDiagnosticPolicy.sourceType("local:abc"))
    }
}
