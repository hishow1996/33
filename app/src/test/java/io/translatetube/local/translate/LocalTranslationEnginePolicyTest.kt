package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranslationEnginePolicyTest {
    @Test
    fun normalizesIso639ThreeLetterCodesForMlKit() {
        assertEquals("en", LangMap.mlkit("eng"))
        assertEquals("ja", LangMap.mlkit("jpn"))
        assertEquals("ko", LangMap.mlkit("kor-KR"))
        assertEquals("ru", LangMap.mlkitSource("rus"))
        assertTrue(LangMap.isMlkitSupported("eng"))
        assertTrue(LangMap.isMlkitSupported("kor"))
    }

    @Test
    fun preservesChineseScriptForThreeLetterCodes() {
        assertEquals("zh-TW", LangMap.mlkit("zho-Hant"))
        assertEquals("zh-TW", LangMap.mlkit("chi-TW"))
        assertEquals("zh", LangMap.mlkit("zho-Hans-CN"))
        assertTrue(SourceLanguagePolicy.isChinese("zho"))
        assertTrue(SourceLanguagePolicy.isChinese("cmn"))
    }

    @Test
    fun normalizesLanguageCodesBeforeLocalModelSupportChecks() {
        assertEquals("de", LocalTranslationEnginePolicy.canonicalOpusSource("deu"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("eng"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("jpn"))

        assertEquals("ja", Small100LanguagePolicy.canonicalCode("jpn"))
        assertEquals("ko", Small100LanguagePolicy.canonicalCode("kor"))
        assertEquals("ru", Small100LanguagePolicy.canonicalCode("rus"))
        assertTrue(Small100LanguagePolicy.supportsSource("jpn"))
        assertTrue(Small100LanguagePolicy.supportsSource("kor"))
        assertTrue(Small100LanguagePolicy.supportsSource("rus"))
    }
}
