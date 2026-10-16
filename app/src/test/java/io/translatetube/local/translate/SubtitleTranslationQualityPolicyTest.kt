package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationQualityPolicyTest {
    @Test
    fun detectsSourceEchoesEvenWhenOnlyTrailingPunctuationDiffers() {
        assertTrue(SubtitleTranslationQualityPolicy.isSourceEcho("Hello world", " hello   WORLD. ", "en"))
        assertTrue(SubtitleTranslationQualityPolicy.isSourceEcho("Bonjour à tous!", "bonjour à tous", "fr"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("Hello world", "你好世界。", "en"))
    }

    @Test
    fun doesNotMarkChineseSourceCaptionsAsMissingTranslations() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("正在翻译", "正在翻译", "zh-Hans"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("你好", "你好", "cmn"))
    }

    @Test
    fun blanksAreHandledAsMissingByTheCallerRatherThanEchoes() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("", "hello", "en"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("hello", " ", "en"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho(null, "hello", "en"))
    }
}
