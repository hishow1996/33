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
}
