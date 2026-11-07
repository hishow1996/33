package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ModelTranslationParserTest {
    @Test
    fun acceptsObjectWrappedJsonTranslationArrays() {
        assertEquals(
            listOf("第一条译文", "第二条译文"),
            parseModelTranslations(
                """{"translations":["第一条译文","第二条译文"]}""",
                2,
            ),
        )
        assertEquals(
            listOf("Hello", "Goodbye"),
            parseModelTranslations(
                """{"results":["Hello","Goodbye"]}""",
                2,
            ),
        )
    }

    @Test
    fun acceptsCommonNumberedDelimitersWithoutChangingRowOrder() {
        assertEquals(
            listOf("第一条", "第二条", "第三条"),
            parseModelTranslations("1. 第一条\n2) 第二条\n3：第三条", 3),
        )
    }

    @Test
    fun acceptsCompleteZeroBasedResultsIncludingEmptyRows() {
        assertEquals(
            listOf("hello", ""),
            parseModelTranslations("0|hello\n1|", 2),
        )
    }

    @Test
    fun acceptsCompleteOneBasedResultsWithoutShiftingTheirOrder() {
        assertEquals(
            listOf("first", "second", "third"),
            parseModelTranslations("1|first\n2|second\n3|third", 3),
        )
    }

    @Test
    fun rejectsMixedZeroAndOneBasedBoundaryMarkersWithSpecificDiagnostic() = runBlocking {
        val trace = TranslationDiagnosticContext("d3c3d51b-fca6-4c59-bce1-5926dcf74a72")
        TranslationDiagnosticContextHolder.withDiagnosticContext(trace) {
            val failure = runCatching {
                parseModelTranslations("0|first\n1|second\n2|extra", 2)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("混用了0起始和1起始边界"))
        }
        assertTrue(trace.snapshot().contains("result=REJECTED_MIXED_INDEX_BASE"))
    }

    @Test
    fun keepsAlignedZeroBasedRowsAndLeavesOmittedTailRowsBlank() {
        assertEquals(
            listOf("hello", "", ""),
            parseModelTranslations("0|hello", 3),
        )
    }

    @Test
    fun keepsAlignedZeroBasedRowsAndLeavesSkippedMiddleRowBlank() {
        assertEquals(
            listOf("a", "", "c"),
            parseModelTranslations("0|a\n2|c", 3),
        )
    }

    @Test
    fun rejectsIncompleteOneBasedResultsBecauseTheyCouldBeShifted() {
        val error = runCatching {
            parseModelTranslations("1|first\n2|second", 3)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("编号不完整"))
    }

    @Test
    fun rejectsDuplicateIndexedRows() {
        val error = runCatching {
            parseModelTranslations("0|first\n0|duplicate\n1|second", 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun rejectsOutOfRangeIndexedRowsEvenWhenOtherRowsLookComplete() {
        val error = runCatching {
            parseModelTranslations("0|first\n1|second\n3|unexpected", 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("超出本批范围"))
    }
    @Test
    fun rejectsVeryLargeOutOfRangeIndexes() {
        val error = runCatching {
            parseModelTranslations("0|first\n1|second\n10000|unexpected", 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("超出本批范围"))
    }

    @Test
    fun rejectsUnnumberedTextMixedIntoIndexedResults() {
        val error = runCatching {
            parseModelTranslations("0|first\n1|second\nextra unnumbered text", 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("混入未编号内容"))
    }

    @Test
    fun keepsPositionalFallbackWhenEveryOutputRowIsUnnumbered() {
        assertEquals(
            listOf("First translation", "第二条译文"),
            parseModelTranslations("First translation\n第二条译文", 2),
        )
    }

    @Test
    fun stripsCommonEnglishAndChinesePreamblesBeforeMappingRows() {
        assertEquals(
            listOf("你好", "再见"),
            parseModelTranslations("Here are the translations:\n你好\n再见", 2),
        )
        assertEquals(
            listOf("早上好", "晚上好"),
            parseModelTranslations("当然，以下是字幕翻译：\n早上好\n晚上好", 2),
        )
    }

    @Test
    fun stripsInlineTranslationLabelFromSingleCueResponses() {
        assertEquals(listOf("你好，世界"), parseModelTranslations("译文：你好，世界", 1))
        assertEquals(listOf("Hello"), parseModelTranslations("Here is the translation: Hello", 1))
    }

    @Test
    fun rejectsCompletelyUnparseableMultiRowOutputWithoutAssumingBatchSplitting() {
        val error = runCatching {
            parseModelTranslations("The service is temporarily unavailable.", 3)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("无法解析为字幕行"))
    }

    @Test
    fun normalizesCaseAndUnderscoreLanguageTags() {
        assertEquals("en", LangMap.mlkit("EN-us"))
        assertEquals("zh", LangMap.mlkit("zh_CN"))
        assertEquals("zh-TW", LangMap.mlkit("zh_Hant_TW"))
        assertEquals("ja", LangMap.mlkit("JA-jp"))
        assertEquals("en", LangMap.mlkitSource("auto"))
        assertEquals("en", LangMap.mlkitSource(""))
        assertEquals("en", LangMap.mlkitSource("und"))
        assertEquals("en", LangMap.mlkitSource("unknown"))
    }

    @Test
    fun canonicalizesGoogleLanguageTagsAndRejectsUnsupportedMlKitTags() {
        assertEquals("en", LangMap.google("EN-us"))
        assertEquals("zh-CN", LangMap.google("zh_Hans", target = true))
        assertEquals("zh-TW", LangMap.google("ZH-hant-TW", target = true))
        assertEquals("zh-TW", LangMap.google("zh_Hant_HK", target = true))
        assertEquals("zh-CN", LangMap.google("zh_Hans_SG", target = true))
        assertEquals("no", LangMap.google("nb"))
        assertEquals("auto", LangMap.google("AUTO"))
        assertEquals("auto", LangMap.google("und"))
        assertEquals("auto", LangMap.google("unknown"))
        assertEquals("英语", LangMap.display("en-US"))
        assertEquals("繁體中文", LangMap.display("zh_Hant_TW"))
        assertEquals("the original language", LangMap.display("und"))
        assertFalse(LangMap.isMlkitSupported("xx-zz"))
    }

    @Test
    fun recordsTheSuccessfulParsingModeAndRowAlignment() = runBlocking {
        val trace = TranslationDiagnosticContext("8caaf8ef-8d5b-437d-b292-5fc859a6e17d")
        TranslationDiagnosticContextHolder.withDiagnosticContext(trace) {
            assertEquals(listOf("你好", "再见"), parseModelTranslations("0|你好\\n1|再见", 2))
        }
        assertTrue(trace.snapshot().contains("result=PARSED_INDEXED_LINES"))
        assertTrue(trace.snapshot().contains("indexBase=0"))
    }

    @Test
    fun recordsWhyNumberedParserRejectedDuplicateIndexes() = runBlocking {
        val trace = TranslationDiagnosticContext("8caaf8ef-8d5b-437d-b292-5fc859a6e17d")
        TranslationDiagnosticContextHolder.withDiagnosticContext(trace) {
            val failure = runCatching {
                parseModelTranslations("0|first\\n0|duplicate\\n1|second", 2)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
        assertTrue(trace.snapshot().contains("result=REJECTED_DUPLICATE_INDEX"))
    }

    @Test
    fun redactsProviderSpecificCredentialAliasesFromDiagnosticUrls() {
        val url = (
            "https://api.example.test/translate?api-key=secret-a&x-goog-api-key=secret-b" +
                "&client_secret=secret-c&access-token=secret-d&monkey=banana&sl=en"
            ).toHttpUrl()
        val diagnostic = safeDiagnosticUrl(url)
        assertFalse(diagnostic.contains("secret-a"))
        assertFalse(diagnostic.contains("secret-b"))
        assertFalse(diagnostic.contains("secret-c"))
        assertFalse(diagnostic.contains("secret-d"))
        assertTrue(diagnostic.contains("monkey=banana"))
        assertTrue(diagnostic.contains("sl=en"))
    }

}
