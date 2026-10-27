package io.translatetube.local.translate

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationRetryBackoffPolicyTest {
    private fun throttle(retryAfterMs: Long? = null) =
        TranslationHttpException("HTTP 429 [POST /v1/chat]: rate limited", retryAfterMs)

    @Test
    fun networkFailuresKeepTheShortTwoRetrySchedule() {
        val error = IOException("connection reset")
        assertEquals(400L, TranslationRetryBackoffPolicy.nextDelayMs(0, error))
        assertEquals(900L, TranslationRetryBackoffPolicy.nextDelayMs(1, error))
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(2, error))
    }

    @Test
    fun throttlingGetsFourLongerRetries() {
        val error = throttle()
        assertEquals(2_000L, TranslationRetryBackoffPolicy.nextDelayMs(0, error))
        assertEquals(4_000L, TranslationRetryBackoffPolicy.nextDelayMs(1, error))
        assertEquals(8_000L, TranslationRetryBackoffPolicy.nextDelayMs(2, error))
        assertEquals(15_000L, TranslationRetryBackoffPolicy.nextDelayMs(3, error))
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(4, error))
    }

    @Test
    fun serviceUnavailableIsTreatedAsThrottling() {
        val error = IllegalStateException("HTTP 503 [POST /x]: overloaded")
        assertEquals(2_000L, TranslationRetryBackoffPolicy.nextDelayMs(0, error))
    }

    @Test
    fun otherServerErrorsOnlyGetTheShortSchedule() {
        val error = IllegalStateException("HTTP 502 [POST /x]: bad gateway")
        assertEquals(400L, TranslationRetryBackoffPolicy.nextDelayMs(0, error))
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(2, error))
    }

    @Test
    fun retryAfterRaisesTheWaitButNeverLowersIt() {
        assertEquals(10_000L, TranslationRetryBackoffPolicy.nextDelayMs(0, throttle(10_000L)))
        assertEquals(2_000L, TranslationRetryBackoffPolicy.nextDelayMs(0, throttle(500L)))
        assertEquals(30_000L, TranslationRetryBackoffPolicy.nextDelayMs(0, throttle(30_000L)))
    }

    @Test
    fun retryAfterLongerThanThirtySecondsGivesUpInsteadOfHanging() {
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(0, throttle(31_000L)))
    }

    @Test
    fun retryAfterIsFoundWhenWrappedInAnotherException() {
        val wrapped = RuntimeException("翻译失败", throttle(12_000L))
        assertEquals(12_000L, TranslationRetryBackoffPolicy.nextDelayMs(0, wrapped))
    }

    @Test
    fun webEnginesDoNotGetTheExtraThrottleRetries() {
        val error = throttle()
        assertEquals(400L, TranslationRetryBackoffPolicy.nextDelayMs(0, error, allowThrottleRetries = false))
        assertEquals(900L, TranslationRetryBackoffPolicy.nextDelayMs(1, error, allowThrottleRetries = false))
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(2, error, allowThrottleRetries = false))
    }

    @Test
    fun permanentFailuresAndCancellationAreNeverRetried() {
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(0, IllegalStateException("HTTP 401: invalid API key")))
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(0, IllegalStateException("invalid JSON response")))
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(0, CancellationException("cancelled")))
        assertNull(TranslationRetryBackoffPolicy.nextDelayMs(-1, IOException("x")))
    }

    @Test
    fun parsesRetryAfterSecondsAndIgnoresEverythingElse() {
        assertEquals(5_000L, TranslationRetryBackoffPolicy.parseRetryAfterMs("5"))
        assertEquals(12_000L, TranslationRetryBackoffPolicy.parseRetryAfterMs(" 12 "))
        assertEquals(0L, TranslationRetryBackoffPolicy.parseRetryAfterMs("0"))
        assertNull(TranslationRetryBackoffPolicy.parseRetryAfterMs(null))
        assertNull(TranslationRetryBackoffPolicy.parseRetryAfterMs(""))
        assertNull(TranslationRetryBackoffPolicy.parseRetryAfterMs("-3"))
        assertNull(TranslationRetryBackoffPolicy.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(TranslationRetryBackoffPolicy.parseRetryAfterMs("1.5"))
        assertEquals(3_600_000L, TranslationRetryBackoffPolicy.parseRetryAfterMs("99999999999999999999"))
    }
}
