package io.translatetube.local.translate

import java.io.IOException
import java.util.concurrent.CancellationException
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
            assertFalse(
                "HTTP $status must not shrink the model batch profile",
                isRecoverableTranslationFormatError(IllegalStateException("HTTP $status: temporary failure")),
            )
        }
        assertTrue(isRecoverableTranslationFormatError(IllegalStateException("HTTP 413: request too large")))
    }

    @Test
    fun recoveryAttemptLimitIsTerminalRatherThanAnotherSplitTrigger() {
        val error = IllegalStateException("拆分恢复已达到单批请求上限 64 次")
        assertFalse(isRecoverableTranslationFormatError(error))
        assertTrue(isTranslationRecoveryAttemptLimit(error))
    }
}
