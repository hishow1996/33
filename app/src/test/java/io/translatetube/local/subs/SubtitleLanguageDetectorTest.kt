package io.translatetube.local.subs

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLanguageDetectorTest {
    @Test
    fun detectsChineseAndJapaneseSeparately() {
        assertEquals("zh", SubtitleLanguageDetector.detect("你好，欢迎观看今天的视频。"))
        assertEquals("ja", SubtitleLanguageDetector.detect("こんにちは、今日の動画へようこそ。"))
    }

    @Test
    fun detectsKoreanAndCommonNonLatinScripts() {
        assertEquals("ko", SubtitleLanguageDetector.detect("안녕하세요. 오늘 영상에 오신 것을 환영합니다."))
        assertEquals("ru", SubtitleLanguageDetector.detect("Привет, добро пожаловать на видео."))
        assertEquals("ar", SubtitleLanguageDetector.detect("مرحبا بكم في الفيديو"))
        assertEquals("th", SubtitleLanguageDetector.detect("สวัสดี ยินดีต้อนรับ"))
    }

    @Test
    fun usesDistinctiveLatinCharactersWhenAvailable() {
        assertEquals("es", SubtitleLanguageDetector.detect("¡Hola! ¿Cómo estás?"))
        assertEquals("pt", SubtitleLanguageDetector.detect("Olá, ação e coração."))
        assertEquals("pl", SubtitleLanguageDetector.detect("Cześć, dziękuję za uwagę."))
    }

    @Test
    fun doesNotAssumeEveryLatinSubtitleIsEnglish() {
        assertEquals("auto", SubtitleLanguageDetector.detect("Bonjour, bienvenue dans cette vidéo."))
        assertEquals("auto", SubtitleLanguageDetector.detect("Hello, welcome to this video."))
        assertEquals("auto", SubtitleLanguageDetector.detect("   "))
    }

    @Test
    fun anOccasionalChineseWordDoesNotMisclassifyAnOtherwiseForeignSubtitleTrack() {
        assertEquals(
            "auto",
            SubtitleLanguageDetector.detect("This English caption mentions 你好 but the sentence is otherwise English."),
        )
        assertEquals(
            "auto",
            SubtitleLanguageDetector.detect("Bonjour, bienvenue. This clip briefly mentions 中国."),
        )
        assertEquals("zh", SubtitleLanguageDetector.detect("你好，欢迎观看今天的视频。"))
    }

    @Test
    fun aFalseChineseProviderHintMustNotSuppressForeignSubtitleTranslation() {
        assertEquals(
            "auto",
            SubtitleLanguageDetector.resolveTranscriptionLanguage(
                "zh",
                "Hello, welcome to this video. Today we are going to talk about the weather.",
            ),
        )
        assertEquals(
            "ja",
            SubtitleLanguageDetector.resolveTranscriptionLanguage("zh", "こんにちは、今日の動画へようこそ。"),
        )
    }

    @Test
    fun transcriptScriptCanCorrectAnIncorrectProviderLanguageHint() {
        assertEquals(
            "zh",
            SubtitleLanguageDetector.resolveTranscriptionLanguage("en", "你好，欢迎观看今天的视频。"),
        )
        assertEquals(
            "en",
            SubtitleLanguageDetector.resolveTranscriptionLanguage("eng", "Hello, welcome to this video."),
        )
        assertEquals(
            "auto",
            SubtitleLanguageDetector.resolveTranscriptionLanguage("zh-CN", "Bonjour, bienvenue dans cette vidéo."),
        )
    }


    @Test
    fun detectsLanguageAcrossMoreThanTheFirstFewShortCues() {
        val cues = listOf("OK", "Yes", "No", "2026", "Hi") +
            List(20) { "こんにちは、今日の動画へようこそ。" }
        assertEquals("ja", SubtitleLanguageDetector.detectFromCues(cues))
        assertEquals("auto", SubtitleLanguageDetector.detectFromCues(cues, maxCues = 0))
    }

    @Test
    fun cueSamplingUsesACharacterBoundForVeryLongCaptions() {
        val cues = listOf("こんにちは".repeat(3_000), "Hello")
        assertEquals("ja", SubtitleLanguageDetector.detectFromCues(cues))
    }


    @Test
    fun resolvesProviderHintsDirectlyFromBoundedTimedCueSamples() {
        val japaneseCues = listOf("OK", "2026", "名前") +
            List(12) { "こんにちは、今日は天気について話します。" }
        assertEquals(
            "ja",
            SubtitleLanguageDetector.resolveTranscriptionLanguageFromCues("zh", japaneseCues),
        )
        val foreignCues = listOf("Hello world", "Welcome back", "Nice to see you")
        assertEquals(
            "auto",
            SubtitleLanguageDetector.resolveTranscriptionLanguageFromCues("zh-CN", foreignCues),
        )
        assertEquals(
            "auto",
            SubtitleLanguageDetector.resolveTranscriptionLanguageFromCues("en", foreignCues, maxCues = 0),
        )
    }

}
