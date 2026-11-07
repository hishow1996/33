package io.translatetube.local.translate

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRetryPolicyTest {
    @Test
    fun retriesTransientNetworkAndProviderFailures() {
        assertTrue(isRetryableTranslationFailure(IOException("connection timed out")))
        assertTrue(isRetryableTranslationFailure(IllegalStateException("HTTP 429 [GET /translate]: rate limited")))
        assertTrue(isRetryableTranslationFailure(IllegalStateException("HTTP 503 [POST /translate]: temporary outage")))
    }

    @Test
    fun doesNotRetryPermanentHttpOrParsingFailures() {
        assertFalse(isRetryableTranslationFailure(IllegalStateException("HTTP 400: invalid request")))
        assertFalse(isRetryableTranslationFailure(IllegalStateException("HTTP 401: invalid API key")))
        assertFalse(isRetryableTranslationFailure(IllegalStateException("HTTP 403: permission denied")))
        assertFalse(isRetryableTranslationFailure(IllegalStateException("invalid JSON response")))
    }

    @Test
    fun cancellationIsNeverRetriedEvenWhenWrapped() {
        assertFalse(isRetryableTranslationFailure(CancellationException("cancelled")))
        assertFalse(
            isRetryableTranslationFailure(
                IllegalStateException("request failed", CancellationException("cancelled")),
            ),
        )
    }

    @Test
    fun transientHttpFailuresAreNotClassifiedAsBatchCapacityFailures() {
        for (status in listOf(408, 425, 429, 500, 502, 503, 504)) {
            assertEquals(
                "HTTP $status must not be treated as a request-capacity failure",
                null,
                translationBatchFailureKind(IllegalStateException("HTTP $status: temporary failure")),
            )
        }
        assertEquals(
            ApiSubtitleBatchFailureKind.INPUT_CAPACITY,
            translationBatchFailureKind(IllegalStateException("HTTP 413: request too large")),
        )
    }

    @Test
    fun recoveryAttemptLimitIsRecognizedAsTerminal() {
        val error = IllegalStateException("单批字幕修复已达到请求上限 64 次")
        assertTrue(isTranslationRecoveryAttemptLimit(error))
    }
}
