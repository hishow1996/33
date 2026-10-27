package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class ModelTranslationParserTest {
    @Test
    fun acceptsCompleteZeroBasedResultsIncludingEmptyRows() {
        assertEquals(
            listOf("hello", ""),
            parseModelTranslations("0|hello\n1|", 2),
        )
    }

    @Test
    fun rejectsIncompleteIndexedResultsInsteadOfReturningMisalignedBlanks() {
        val error = runCatching {
            parseModelTranslations("0|hello", 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
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
            parseModelTranslations("First translation\\n第二条译文", 2),
        )
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
