package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFactorySelectionTest {
    @Test
    fun everyAvailableLocalTranslationModelIsRecognizedAsFirstClassEngine() {
        val ids = listOf(
            EngineFactory.ID_LOCAL_MLKIT,
            EngineFactory.ID_LOCAL_OPUS,
            EngineFactory.ID_LOCAL_SMALL100,
            EngineFactory.ID_LOCAL_SINGLE_PAIR,
        )

        assertTrue(ids.all(EngineFactory::isLocalTranslationEngine))
        assertEquals("ML Kit", EngineFactory.localTranslationEngineName(EngineFactory.ID_LOCAL_MLKIT))
        assertEquals("OPUS-MT", EngineFactory.localTranslationEngineName(EngineFactory.ID_LOCAL_OPUS))
        assertEquals("SMaLL-100", EngineFactory.localTranslationEngineName(EngineFactory.ID_LOCAL_SMALL100))
        assertEquals("单语言对翻译模型", EngineFactory.localTranslationEngineName(EngineFactory.ID_LOCAL_SINGLE_PAIR))
    }

    @Test
    fun singlePairLanguagePolicySupportsJapaneseKoreanAndRussianOnly() {
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ja"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ja-JP"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("jpn"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("ko-KR"))
        assertTrue(SinglePairModelLanguagePolicy.supportsSource("rus-RU"))
        assertFalse(SinglePairModelLanguagePolicy.supportsSource("en"))
        assertFalse(SinglePairModelLanguagePolicy.supportsSource("de"))
    }

    @Test
    fun onlineBuiltinsAreNotMisclassifiedAsLocalModels() {
        val ids = listOf(
            EngineFactory.ID_GOOGLE,
            EngineFactory.ID_GOOGLE_ALT,
            EngineFactory.ID_MICROSOFT,
            EngineFactory.ID_BAIDU,
            EngineFactory.ID_DEEPL,
            EngineFactory.ID_FREE_GEMINI,
            EngineFactory.ID_FREE_DEEPSEEK,
        )

        assertFalse(ids.any(EngineFactory::isLocalTranslationEngine))
        assertTrue(ids.all { EngineFactory.localTranslationEngineName(it) == null })
    }
}
