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
}
