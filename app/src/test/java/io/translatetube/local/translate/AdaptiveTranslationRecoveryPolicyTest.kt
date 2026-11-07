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
    fun adaptiveRecoveryReducesByFiveRowsAndNeverSplitsBelowFive() = runBlocking {
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
        assertTrue(error?.message.orEmpty().contains("最小恢复批次5条"))
        assertEquals(listOf(30, 25, 20, 15, 10, 5), requests)
        assertTrue(requests.all { it >= MIN_TRANSLATION_RECOVERY_BATCH_ROWS })
    }

    @Test
    fun malformedOutputGetsOneSameSizeStrictRetryBeforeAnySplit() = runBlocking {
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
    }

    @Test
    fun capacityFailureSkipsSameSizeFormatRetryAndKeepsFiveRowRecovery() = runBlocking {
        val requests = mutableListOf<Int>()
        var formatRepairCalls = 0
        var summary: TranslationRecoverySummary? = null
        val source = List(10) { "cue-$it" }
        val translated = translateBatchWithAdaptiveRecovery(
            lines = source,
            retryMalformedBatch = { rows, _ ->
                formatRepairCalls++
                rows.map { "修复:$it" }
            },
            onRecoverySummary = { summary = it },
        ) { rows, _ ->
            requests += rows.size
            if (rows.size > MIN_TRANSLATION_RECOVERY_BATCH_ROWS) {
                throw IllegalStateException("HTTP 413: Payload Too Large")
            }
            rows.map { "译:$it" }
        }

        assertEquals(source.map { "译:$it" }, translated)
        assertEquals(listOf(10, 5, 5), requests)
        assertEquals(0, formatRepairCalls)
        assertTrue(summary?.failureKinds?.contains(ApiSubtitleBatchFailureKind.INPUT_CAPACITY) == true)
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
    fun recoverySummaryReportsLargestSuccessfulLeafAndTotalAttempts() = runBlocking {
        var summary: TranslationRecoverySummary? = null
        val lines = List(30) { "subtitle $it" }
        val result = translateBatchWithAdaptiveRecovery(
            lines = lines,
            onRecoverySummary = { summary = it },
            translate = { rows, _ ->
                if (rows.size > 10) {
                    throw IllegalStateException("翻译服务返回的字幕编号不完整")
                }
                rows.map { "译文-$it" }
            },
        )
        assertEquals(lines.size, result.size)
        assertTrue(summary?.recoveredBySplitting == true)
        assertEquals(10, summary?.largestSuccessfulBatchRows ?: 0)
        assertTrue((summary?.attempts ?: 0) > 1)
    }
}
