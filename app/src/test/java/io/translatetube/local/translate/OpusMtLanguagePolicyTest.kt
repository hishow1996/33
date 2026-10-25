package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.translatetube.local.subs.SubtitleScriptLanguageHint
import org.junit.Test

class OpusMtLanguagePolicyTest {
    @Test
    fun supportsJapaneseKoreanAndRussianForChineseOutput() {
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("ja"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("ko"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("ru"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("ja-JP"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("ko-KR"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("ru-RU"))
    }

    @Test
    fun onlyJapaneseKoreanAndRussianUseEnglishPivot() {
        assertTrue(LocalTranslationEnginePolicy.usesEnglishPivot("ja"))
        assertTrue(LocalTranslationEnginePolicy.usesEnglishPivot("ko"))
        assertTrue(LocalTranslationEnginePolicy.usesEnglishPivot("ru"))
        assertTrue(LocalTranslationEnginePolicy.usesEnglishPivot("jpn"))
        assertTrue(LocalTranslationEnginePolicy.usesEnglishPivot("kor"))
        assertTrue(LocalTranslationEnginePolicy.usesEnglishPivot("rus"))
        assertFalse(LocalTranslationEnginePolicy.usesEnglishPivot("en"))
        assertFalse(LocalTranslationEnginePolicy.usesEnglishPivot("de"))
        assertFalse(LocalTranslationEnginePolicy.usesEnglishPivot("zh-CN"))
    }

    @Test
    fun normalizesIso639AliasesBeforeChoosingModels() {
        assertEquals("en", LocalTranslationEnginePolicy.canonicalOpusSource("eng"))
        assertEquals("de", LocalTranslationEnginePolicy.canonicalOpusSource("deu-DE"))
        assertEquals("fi", LocalTranslationEnginePolicy.canonicalOpusSource("fin"))
        assertEquals("sv", LocalTranslationEnginePolicy.canonicalOpusSource("swe"))
        assertEquals("ja", LocalTranslationEnginePolicy.canonicalOpusSource("jpn-JP"))
        assertEquals("ko", LocalTranslationEnginePolicy.canonicalOpusSource("kor-KR"))
        assertEquals("ru", LocalTranslationEnginePolicy.canonicalOpusSource("rus-RU"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("jpn-JP"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("kor-KR"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("rus-RU"))
    }

    @Test
    fun detectsShortJapaneseAndKoreanCaptionsByDistinctiveScripts() {
        assertEquals("ja", SubtitleScriptLanguageHint.detect("こんにちは"))
        assertEquals("ko", SubtitleScriptLanguageHint.detect("안녕하세요"))
        assertEquals(null, SubtitleScriptLanguageHint.detect("日本語"))
        assertEquals(null, SubtitleScriptLanguageHint.detect("こんにちは 안녕하세요"))
    }
}
