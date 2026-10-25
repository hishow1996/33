package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertFalse(LocalTranslationEnginePolicy.usesEnglishPivot("en"))
        assertFalse(LocalTranslationEnginePolicy.usesEnglishPivot("de"))
        assertFalse(LocalTranslationEnginePolicy.usesEnglishPivot("zh-CN"))
    }
}
