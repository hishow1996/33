package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AdaptiveTranslationRecoveryPolicyTest {
    @Test
    fun contextAndPayloadLimitFailuresAreClassifiedForBudgetDiagnostics() {
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
    fun batchFailureClassificationSeparatesFormatInputAndOutputCapacity() {
        assertEquals(
            ApiSubtitleBatchFailureKind.RESPONSE_FORMAT,
            translationBatchFailureKind(IllegalStateException("翻译服务返回的字幕编号不完整")),
        )
        assertEquals(
            ApiSubtitleBatchFailureKind.INPUT_CAPACITY,
            translationBatchFailureKind(IllegalStateException("HTTP 400: context_length_exceeded")),
        )
        assertEquals(
            ApiSubtitleBatchFailureKind.OUTPUT_CAPACITY,
            translationBatchFailureKind(IllegalStateException("finish_reason=length")),
        )
        assertEquals(
            null,
            translationBatchFailureKind(IllegalStateException("HTTP 429: rate limit exceeded")),
        )
    }

    @Test
    fun existingSubtitleAlignmentErrorsAreClassifiedForRepair() {
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
    fun formatFailureNeverShrinksTheFixedBatch() = runBlocking {
        val requests = mutableListOf<Int>()
        val error = runCatching {
            translateBatchWithAdaptiveRecovery(
                lines = List(30) { "subtitle $it" },
                translate = { rows, _ ->
                    requests += rows.size
                    throw IllegalStateException("翻译服务返回的字幕编号不完整")
                },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(listOf(30), requests)
        assertTrue(error?.message.orEmpty().contains("固定批次 30 条"))
        assertTrue(error?.message.orEmpty().contains("不会自动缩小后续批次"))
    }

    @Test
    fun malformedOutputGetsOneSameSizeStrictRetry() = runBlocking {
        val source = List(10) { "cue-$it" }
        val normalCalls = mutableListOf<Int>()
        val repairCalls = mutableListOf<Int>()
        var summary: TranslationRecoverySummary? = null
        val translated = translateBatchWithAdaptiveRecovery(
            lines = source,
            retryMalformedBatch = { rows, _ ->
                repairCalls += rows.size
                rows.map { "译:$it" }
            },
            onRecoverySummary = { summary = it },
        ) { rows, _ ->
            normalCalls += rows.size
            throw IllegalStateException("翻译服务返回的字幕编号不完整")
        }

        assertEquals(source.map { "译:$it" }, translated)
        assertEquals(listOf(10), normalCalls)
        assertEquals(listOf(10), repairCalls)
        assertEquals(2, summary?.attempts)
        assertTrue(summary?.failureKinds?.contains(ApiSubtitleBatchFailureKind.RESPONSE_FORMAT) == true)
        assertTrue(summary?.hadRecoverableFailure == true)
        assertEquals(false, summary?.recoveredBySplitting)
    }

    @Test
    fun capacityFailureDoesNotRetryAsFormatOrResizeTheBatch() = runBlocking {
        val requests = mutableListOf<Int>()
        var formatRepairCalls = 0
        var summary: TranslationRecoverySummary? = null
        val source = List(10) { "cue-$it" }
        val error = runCatching {
            translateBatchWithAdaptiveRecovery(
                lines = source,
                retryMalformedBatch = { rows, _ ->
                    formatRepairCalls++
                    rows.map { "修复:$it" }
                },
                onRecoverySummary = { summary = it },
            ) { rows, _ ->
                requests += rows.size
                throw IllegalStateException("HTTP 413: Payload Too Large")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(listOf(10), requests)
        assertEquals(0, formatRepairCalls)
        assertTrue(summary?.failureKinds?.contains(ApiSubtitleBatchFailureKind.INPUT_CAPACITY) == true)
        assertTrue(error?.message.orEmpty().contains("固定批次 10 条"))
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

    @Test
    fun sameBatchRetrySummaryReportsTheOriginalBatchSizeAndAttemptCount() = runBlocking {
        var summary: TranslationRecoverySummary? = null
        val lines = List(30) { "subtitle $it" }
        val result = translateBatchWithAdaptiveRecovery(
            lines = lines,
            retryMalformedBatch = { rows, _ -> rows.map { "译文-$it" } },
            onRecoverySummary = { summary = it },
            translate = { _, _ ->
                throw IllegalStateException("翻译服务返回的字幕编号不完整")
            },
        )

        assertEquals(lines.size, result.size)
        assertEquals(false, summary?.recoveredBySplitting)
        assertEquals(30, summary?.largestSuccessfulBatchRows ?: 0)
        assertEquals(2, summary?.attempts)
    }


}
