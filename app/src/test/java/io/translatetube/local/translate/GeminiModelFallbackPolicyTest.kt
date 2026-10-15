package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelFallbackPolicyTest {
    @Test
    fun fallsBackWhenGoogleReportsTheSelectedModelIsRetired() {
        val error = IllegalStateException(
            """HTTP 404: {"error":{"code":404,"message":"This model models/gemini-2.5-flash is no longer available to new users","status":"NOT_FOUND"}}"""
        )
        assertTrue(shouldFallbackToCurrentGeminiModel("gemini-2.5-flash", error))
    }

    @Test
    fun doesNotFallbackForAuthenticationOrUnrelatedErrors() {
        assertFalse(
            shouldFallbackToCurrentGeminiModel(
                "gemini-2.5-flash",
                IllegalStateException("HTTP 403: API key is invalid"),
            )
        )
        assertFalse(
            shouldFallbackToCurrentGeminiModel(
                "gemini-3.8-flash",
                IllegalStateException("HTTP 404: model not found"),
            )
        )
    }
}
