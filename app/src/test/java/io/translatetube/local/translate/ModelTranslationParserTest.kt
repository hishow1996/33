package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun normalizesCaseAndUnderscoreLanguageTags() {
        assertEquals("en", LangMap.mlkit("EN-us"))
        assertEquals("zh", LangMap.mlkit("zh_CN"))
        assertEquals("zh-TW", LangMap.mlkit("zh_Hant_TW"))
        assertEquals("ja", LangMap.mlkit("JA-jp"))
        assertEquals("en", LangMap.mlkitSource("auto"))
        assertEquals("en", LangMap.mlkitSource(""))
    }

    @Test
    fun canonicalizesGoogleLanguageTagsAndRejectsUnsupportedMlKitTags() {
        assertEquals("en", LangMap.google("EN-us"))
        assertEquals("zh-CN", LangMap.google("zh_Hans", target = true))
        assertEquals("zh-TW", LangMap.google("ZH-hant-TW", target = true))
        assertEquals("auto", LangMap.google("AUTO"))
        assertFalse(LangMap.isMlkitSupported("xx-zz"))
    }

}
