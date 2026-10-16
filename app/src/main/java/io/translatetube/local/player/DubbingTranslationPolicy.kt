package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleLanguageDetector
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.translate.SubtitleTranslationQualityPolicy

/** Final guard before subtitle text becomes spoken audio or a reusable PCM cache key. */
internal object DubbingTranslationPolicy {
    fun usableText(sourceText: String, translatedText: String?): String? {
        if (sourceText.isBlank()) return null
        val detectedSource = SubtitleLanguageDetector.detect(sourceText)
        if (detectedSource == "zh" || detectedSource == "yue") {
            // Preserve literal Chinese captions even if their text resembles a UI progress label.
            return sourceText.trim()
        }
        val cleaned = SubtitleParsers.cleanTranslatedText(translatedText) ?: return null
        if (SubtitleTranslationQualityPolicy.isSourceEcho(sourceText, cleaned, detectedSource)) return null
        return cleaned
    }
}