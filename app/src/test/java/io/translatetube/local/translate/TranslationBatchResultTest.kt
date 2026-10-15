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
    fun duplicateSourceIndicesProduceAtMostOneTranslationOrRetryEntry() {
        val result = TranslationBatchResult.from(
            listOf(2, 2, 3, 3, 4),
            listOf("", "第二行", "第三行", "", "第四行"),
        )

        assertEquals(mapOf(2 to "第二行", 3 to "第三行", 4 to "第四行"), result.translations)
        assertTrue(result.missingIndices.isEmpty())
    }

}
