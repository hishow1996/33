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
    fun treatsShortModelResponsesAsMissingRowsRatherThanShiftingAlignment() {
        val result = TranslationBatchResult.from(
            listOf(10, 12, 20),
            listOf("第十行译文"),
        )

        assertEquals(mapOf(10 to "第十行译文"), result.translations)
        assertEquals(listOf(12, 20), result.missingIndices)
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

}
