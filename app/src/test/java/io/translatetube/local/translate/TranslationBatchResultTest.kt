package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchResultTest {
    @Test
    fun preservesRowIndicesAndOrderWhenEveryTranslationIsValid() {
        val result = TranslationBatchResult.from(
            listOf(4, 8, 11),
            listOf("第一句译文", "Second translation", "第三句译文"),
        )

        assertEquals(
            mapOf(4 to "第一句译文", 8 to "Second translation", 11 to "第三句译文"),
            result.translations,
        )
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun marksProgressPlaceholdersAndBlankRowsAsMissing() {
        val result = TranslationBatchResult.from(
            listOf(2, 3, 9),
            listOf("有效译文", "正在翻译", "   "),
        )

        assertEquals(mapOf(2 to "有效译文"), result.translations)
        assertEquals(listOf(3, 9), result.missingIndices)
    }

    @Test
    fun rejectsShortResponsesWithoutGuessingWhichSubtitleRowsTheyBelongTo() {
        val result = TranslationBatchResult.from(
            listOf(10, 12, 20),
            listOf("无法确认对应哪一行的译文"),
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(10, 12, 20), result.missingIndices)
    }

    @Test
    fun rejectsLongResponsesWithoutGuessingWhichSubtitleRowsTheyBelongTo() {
        val result = TranslationBatchResult.from(
            listOf(10, 12),
            listOf("第一行", "第二行", "多余的一行"),
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(10, 12), result.missingIndices)
    }

    @Test
    fun rejectsEnglishParaphrasesForChineseSubtitleOutput() {
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1, 2),
            rawTranslations = listOf("I think so.", "你好", "J. K. Rowling"),
            sourceTexts = mapOf(
                0 to "I guess so.",
                1 to "Hello",
                2 to "J. K. Rowling",
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertEquals(mapOf(1 to "你好", 2 to "J. K. Rowling"), result.translations)
        assertEquals(listOf(0), result.missingIndices)
    }

    @Test
    fun treatsEmptyOrStatusOnlyResponsesAsMissing() {
        val result = TranslationBatchResult.from(listOf(0, 1), listOf("", "翻译中 1/2 行"))

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(0, 1), result.missingIndices)
    }

    @Test
    fun negativeSourceIndicesAreIgnoredInsteadOfBecomingCacheKeys() {
        val result = TranslationBatchResult.from(
            listOf(-1, 2, -8, 3),
            listOf("must not be stored", "valid", "must not be retried", "正在翻译"),
        )
        assertEquals(mapOf(2 to "valid"), result.translations)
        assertEquals(listOf(3), result.missingIndices)
    }

    @Test
    fun duplicateSourceIndicesProduceAtMostOneTranslationOrRetryEntry() {
        val result = TranslationBatchResult.from(
            listOf(2, 2, 3, 3, 4),
            listOf("", "第二行", "第三行", "", "第四行"),
        )

        assertEquals(mapOf(2 to "第二行", 3 to "第三行", 4 to "第四行"), result.translations)
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun rejectsSourceTextEchoesAndRetriesOnlyThoseSubtitleRows() {
        val result = TranslationBatchResult.from(
            indices = listOf(5, 8, 9),
            rawTranslations = listOf("Bonjour à tous", "Hello world", "你好"),
            sourceTexts = mapOf(
                5 to "Bonjour à tous",
                8 to "Hello world",
                9 to "Hello there",
            ),
            sourceLanguage = "en",
        )

        assertEquals(mapOf(9 to "你好"), result.translations)
        assertEquals(listOf(5, 8), result.missingIndices)
    }

    @Test
    fun acceptsGoogleSourceEchoAsAnUntranslatedCueWithoutFailingOtherRows() {
        val result = TranslationBatchResult.from(
            indices = listOf(1, 4, 7),
            rawTranslations = listOf("你好世界", "J. K. Rowling", "正在翻译"),
            sourceTexts = mapOf(
                1 to "Hello world",
                4 to "J. K. Rowling",
                7 to "This row is still missing",
            ),
            sourceLanguage = "en",
            sourceEchoAsUntranslatedFallback = true,
        )

        // 人名/缩写原样返回是正确的翻译，保留下来才能被配音念出来，而不是留空回退原声。
        assertEquals(mapOf(1 to "你好世界", 4 to "J. K. Rowling"), result.translations)
        assertEquals(listOf(7), result.missingIndices)
    }

    @Test
    fun sourceEchoFallbackDoesNotAcceptActuallyMissingTranslations() {
        val result = TranslationBatchResult.from(
            indices = listOf(2, 3),
            rawTranslations = listOf("", "SomeBrandName"),
            sourceTexts = mapOf(2 to "Hello there", 3 to "SomeBrandName"),
            sourceLanguage = "en",
            sourceEchoAsUntranslatedFallback = true,
        )

        assertEquals(mapOf(3 to "SomeBrandName"), result.translations)
        assertEquals(listOf(2), result.missingIndices)
    }

    @Test
    fun allowsIdenticalChineseSourceTextWhenTheSourceIsAlreadyChinese() {
        val result = TranslationBatchResult.from(
            indices = listOf(1, 2),
            rawTranslations = listOf("你好世界", "保留原声"),
            sourceTexts = mapOf(1 to "你好世界", 2 to "保留原声"),
            sourceLanguage = "zh-Hans",
        )

        assertEquals(mapOf(1 to "你好世界", 2 to "保留原声"), result.translations)
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun retriesEchoRowsWhenTheProviderOnlyChangesFinalPunctuation() {
        val result = TranslationBatchResult.from(
            indices = listOf(1, 2),
            rawTranslations = listOf("Hello world.", "你好世界。"),
            sourceTexts = mapOf(1 to "Hello world", 2 to "Hello there"),
            sourceLanguage = "en",
        )
        assertEquals(mapOf(2 to "你好世界。"), result.translations)
        assertEquals(listOf(1), result.missingIndices)
    }

    @Test
    fun exposesSpecificReasonsForRejectedSubtitleRows() {
        val result = TranslationBatchResult.from(
            indices = listOf(252, 253, 254),
            rawTranslations = listOf("I think so.", "Hello there.", ""),
            sourceTexts = mapOf(
                252 to "I guess so.",
                253 to "Hello there.",
                254 to "Where are you?",
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertEquals(listOf(252, 253, 254), result.missingIndices)
        assertEquals(
            listOf(
                "TARGET_LANGUAGE_MISMATCH",
                "SOURCE_ECHO_AND_TARGET_LANGUAGE_MISMATCH",
                "EMPTY_OR_STATUS_PLACEHOLDER",
            ),
            result.rejectedRows.map { it.reasonCode },
        )
        assertEquals(listOf(11, 12, 14), result.rejectedRows.map { it.sourceCharCount })
        assertEquals(listOf(11, 12, 0), result.rejectedRows.map { it.rawOutputCharCount })
    }

    @Test
    fun identifiesEveryAffectedRowWhenResponseLengthDoesNotMatchRequest() {
        val result = TranslationBatchResult.from(
            indices = listOf(10, 12),
            rawTranslations = listOf("only one output"),
            sourceTexts = mapOf(10 to "First source", 12 to "Second source"),
        )

        assertEquals(listOf(10, 12), result.missingIndices)
        assertEquals(listOf("ROW_COUNT_MISMATCH", "ROW_COUNT_MISMATCH"), result.rejectedRows.map { it.reasonCode })
        assertEquals(listOf(12, 13), result.rejectedRows.map { it.sourceCharCount })
    }
}
