package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

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
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("API 未返回可用翻译文本；finish_reason=length")
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
        assertTrue(
            isRecoverableTranslationFormatError(
                IllegalStateException("翻译服务在编号译文中混入未编号内容")
            )
        )
    }

    @Test
    fun adaptiveRecoveryStopsAtSharedPerBatchRequestBudget() = runBlocking {
        var requests = 0
        val error = runCatching {
            translateBatchWithAdaptiveRecovery(
                lines = List(12) { "subtitle $it" },
                translate = { _, _ ->
                    requests++
                    throw IllegalStateException("翻译服务返回的字幕编号不完整")
                },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("单批请求上限"))
        assertTrue(requests <= MAX_ADAPTIVE_TRANSLATION_ATTEMPTS_PER_BATCH)
        assertEquals(MAX_ADAPTIVE_TRANSLATION_ATTEMPTS_PER_BATCH, requests)
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
