package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleLanguageDetector
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.translate.SubtitleTranslationQualityPolicy

/** Final guard before subtitle text becomes spoken audio or a reusable PCM cache key. */
internal object DubbingTranslationPolicy {
    fun usableText(
        sourceText: String,
        translatedText: String?,
        sourceLanguage: String = "auto",
    ): String? {
        if (sourceText.isBlank()) return null
        val hint = io.translatetube.local.translate.SourceLanguagePolicy.normalizeTag(sourceLanguage)
        val hasLanguageHint = !io.translatetube.local.translate.SourceLanguagePolicy.isUndetermined(hint)
        // A video-level language is more reliable than guessing from one short cue. In particular,
        // Japanese captions written only in Kanji can otherwise be mistaken for Chinese.
        val detectedSource = if (hasLanguageHint) hint.lowercase(java.util.Locale.ROOT)
            else SubtitleLanguageDetector.detect(sourceText)
        val chineseSource = io.translatetube.local.translate.SourceLanguagePolicy.isChinese(detectedSource)
        if (chineseSource) {
            // Preserve literal Chinese captions even if their text resembles a UI progress label.
            return sourceText.trim()
        }
        val cleaned = SubtitleParsers.cleanTranslatedText(translatedText) ?: return null
        if (SubtitleTranslationQualityPolicy.isSourceEcho(sourceText, cleaned, detectedSource)) return null
        return cleaned
    }
}