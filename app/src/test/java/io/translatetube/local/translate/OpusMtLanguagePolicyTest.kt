package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.translatetube.local.subs.SubtitleScriptLanguageHint
import org.junit.Test

class OpusMtLanguagePolicyTest {
    @Test
    fun supportsOnlyVerifiedDirectChineseModels() {
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("en"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("de"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("fi"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("sv"))
        assertTrue(LocalTranslationEnginePolicy.supportsOpusSource("de-DE"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("ja"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("ja-JP"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("ko"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("ko-KR"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("ru"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("ru-RU"))
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
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("jpn-JP"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("kor-KR"))
        assertFalse(LocalTranslationEnginePolicy.supportsOpusSource("rus-RU"))
    }

    @Test
    fun validatesResumeContentRangeBeforeAppendingDownloadedBytes() {
        assertTrue(ModelDownloadRangePolicy.matches("bytes 100-999/1000", 100L, 1000L))
        assertFalse(ModelDownloadRangePolicy.matches("bytes 99-999/1000", 100L, 1000L))
        assertFalse(ModelDownloadRangePolicy.matches("bytes 100-900/1000", 100L, 1000L))
        assertFalse(ModelDownloadRangePolicy.matches("bytes 100-999/1001", 100L, 1000L))
        assertFalse(ModelDownloadRangePolicy.matches("bytes */1000", 100L, 1000L))
        assertFalse(ModelDownloadRangePolicy.matches(null, 100L, 1000L))
    }

    @Test
    fun detectsShortJapaneseAndKoreanCaptionsByDistinctiveScripts() {
        assertEquals("ja", SubtitleScriptLanguageHint.detect("こんにちは"))
        assertEquals("ko", SubtitleScriptLanguageHint.detect("안녕하세요"))
        assertEquals(null, SubtitleScriptLanguageHint.detect("日本語"))
        assertEquals(null, SubtitleScriptLanguageHint.detect("こんにちは 안녕하세요"))
    }
}
