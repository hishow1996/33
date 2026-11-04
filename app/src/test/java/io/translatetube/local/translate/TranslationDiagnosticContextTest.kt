package io.translatetube.local.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationDiagnosticContextTest {
    @Test
    fun carriesOneDiagnosticIdAcrossTransportAndRetryEvents() = runBlocking {
        val trace = TranslationDiagnosticContext("8caaf8ef-8d5b-437d-b292-5fc859a6e17d")
        assertNull(TranslationDiagnosticContextHolder.current())

        TranslationDiagnosticContextHolder.withDiagnosticContext(trace) {
            assertEquals(trace, TranslationDiagnosticContextHolder.current())
            trace.recordHttpResponse(
                method = "POST",
                route = "POST https://api.example.test/[URL path/query hidden]",
                statusCode = 429,
                requestId = "req-123",
                contentType = "application/json",
                responseBytes = 85,
                retryAfterHeader = "3",
                retryAfterMs = 3_000,
                rateLimitHeaders = "x-ratelimit-remaining=0",
                bodySummary = "providerErrorCode=rate_limit_exceeded",
            )
            trace.recordRetry(
                retryNumber = 1,
                waitMs = 3_000,
                failureType = "RATE_LIMITED",
                failureMessage = "HTTP 429",
            )
            trace.recordParserEvent("openai-compatible", "FAILED", "missing choices")
        }

        assertNull(TranslationDiagnosticContextHolder.current())
        val snapshot = trace.snapshot()
        assertTrue(snapshot.contains("HTTP_RESPONSE"))
        assertTrue(snapshot.contains("requestId=req-123"))
        assertTrue(snapshot.contains("TRANSPORT_RETRY #1 waitMs=3000"))
        assertTrue(snapshot.contains("RESPONSE_PARSER parser=openai-compatible"))
    }

    @Test
    fun snapshotIsBoundedAndKeepsTheLatestTransportEvents() {
        val trace = TranslationDiagnosticContext("8caaf8ef-8d5b-437d-b292-5fc859a6e17d")
        repeat(60) { index ->
            trace.recordParserEvent("parser", "FAILED", "event-$index")
        }

        val snapshot = trace.snapshot(500)
        assertTrue(snapshot.length <= 600)
        assertTrue(snapshot.contains("event-59"))
        assertTrue(!snapshot.contains("event-0"))
    }

    @Test
    fun diagnosticIdIsAvailableOnlyInsideItsCoroutineContext() = runBlocking {
        val trace = TranslationDiagnosticContext("diagnostic-123")
        assertNull(TranslationDiagnosticContextHolder.current())
        TranslationDiagnosticContextHolder.withDiagnosticContext(trace) {
            assertNotNull(TranslationDiagnosticContextHolder.current())
            assertEquals("diagnostic-123", TranslationDiagnosticContextHolder.current()?.diagnosticId)
        }
        assertNull(TranslationDiagnosticContextHolder.current())
    }
}
