package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTranslationRecoveryPolicyTest {
    @Test
    fun contextAndPayloadLimitFailuresCanRetryWithSmallerBatches() {
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("HTTP 400: maximum context length exceeded")
            )
        )
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("HTTP 400: context_length_exceeded")
            )
        )
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("HTTP 413: Payload Too Large")
            )
        )
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("Request body too large for selected model")
            )
        )
    }

    @Test
    fun existingSubtitleAlignmentErrorsRemainRecoverable() {
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("翻译服务返回的字幕编号不完整")
            )
        )
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("翻译服务重复返回字幕编号")
            )
        )
    }

    @Test
    fun authenticationRateLimitAndServerFailuresAreNotSplitIntoSmallerBatches() {
        assertFalse(
            isRecoverableTranslationFormatError(
                IllegalStateException("HTTP 401: invalid API key")
            )
        )
        assertFalse(
            isRecoverableTranslationFormatError(
                IllegalStateException("HTTP 429: rate limit exceeded")
            )
        )
        assertFalse(
            isRecoverableTranslationFormatError(
                IllegalStateException("HTTP 503: service unavailable")
            )
        )
        assertFalse(
            isRecoverableTranslationFormatError(
                IllegalStateException("HTTP 400: invalid model ID")
            )
        )
    }
}
