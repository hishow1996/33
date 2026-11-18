package io.translatetube.local.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    private fun assertScrubbed(input: String, vararg secrets: String) {
        val out = LogRedactor.redact(input)
        secrets.forEach { assertFalse("$it leaked in: $out", out.contains(it)) }
    }

    @Test
    fun redactsMultiTokenAuthorizationAndCookieHeaders() {
        assertScrubbed("Authorization: Token abc123SECRET", "abc123SECRET")
        assertScrubbed("authorization: DeepL-Auth-Key 1234-abcd-SECRETKEY:fx", "SECRETKEY")
        assertScrubbed("Cookie: sid=AAA; token=BBB; other=CCC", "AAA", "BBB", "CCC")
        assertScrubbed("Set-Cookie: s=ZZZ; Path=/", "ZZZ")
        assertScrubbed("x-goog-api-key: AIzaSyEXAMPLEKEY123", "AIzaSyEXAMPLEKEY123")
    }

    @Test
    fun redactsCloudVendorStyleSecrets() {
        assertScrubbed("""{"secretKey":"SK_VALUE","accessKeySecret":"AKS_VALUE","name":"ok"}""", "SK_VALUE", "AKS_VALUE")
        assertScrubbed("secretKey=SK2VALUE, region=ap", "SK2VALUE")
    }

    @Test
    fun keepsRedactingQueryKeysUserInfoAndBearer() {
        assertScrubbed("GET https://x.test/v2?key=QUERYKEY&q=hi", "QUERYKEY")
        assertScrubbed("https://user:pass@host.test/path", "user:pass")
        assertScrubbed("authorization=Bearer secret-token", "secret-token")
    }

    @Test
    fun leavesDiagnosticTextReadable() {
        val out = LogRedactor.redact("HTTP 429 [POST https://api.x.test/v1/chat] model=gpt rows=12")
        assertTrue(out.contains("HTTP 429") && out.contains("rows=12"))
    }
}
