package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleLanguageDetector
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.translate.SubtitleTranslationQualityPolicy

/** Translation result plus the specific reason that prevents it from becoming spoken audio. */
internal data class DubbingTranslationResolution(
    val text: String?,
    val reason: String? = null,
)

/** Final guard before subtitle text becomes spoken audio or a reusable PCM cache key. */
internal object DubbingTranslationPolicy {
    fun resolveText(
        sourceText: String,
        translatedText: String?,
        sourceLanguage: String = "auto",
    ): DubbingTranslationResolution {
        if (sourceText.isBlank()) {
            return DubbingTranslationResolution(null, "原文字幕为空")
        }
        val hint = io.translatetube.local.translate.SourceLanguagePolicy.normalizeTag(sourceLanguage)
        val hasLanguageHint = !io.translatetube.local.translate.SourceLanguagePolicy.isUndetermined(hint)
        // A video-level language is more reliable than guessing from one short cue. In particular,
        // Japanese captions written only in Kanji can otherwise be mistaken for Chinese.
        val detectedSource = if (hasLanguageHint) hint.lowercase(java.util.Locale.ROOT)
            else SubtitleLanguageDetector.detect(sourceText)
        val chineseSource = io.translatetube.local.translate.SourceLanguagePolicy.isChinese(detectedSource)
        if (chineseSource) {
            // Preserve literal Chinese captions even if their text resembles a UI progress label.
            return DubbingTranslationResolution(sourceText.trim())
        }
        if (translatedText.isNullOrBlank()) {
            return DubbingTranslationResolution(null, "翻译结果为空或只有空白字符")
        }
        val cleaned = SubtitleParsers.cleanTranslatedText(translatedText)
            ?: return DubbingTranslationResolution(
                null,
                "原始译文非空，但清理占位内容/非字幕文本后为空",
            )
        if (cleaned.isBlank()) {
            return DubbingTranslationResolution(
                null,
                "原始译文非空，但清理占位内容/非字幕文本后为空",
            )
        }
        if (SubtitleTranslationQualityPolicy.isSourceEcho(sourceText, cleaned, detectedSource)) {
            return DubbingTranslationResolution(null, "译文与原文相同，命中原文回显过滤规则")
        }
        return DubbingTranslationResolution(cleaned)
    }

    fun usableText(
        sourceText: String,
        translatedText: String?,
        sourceLanguage: String = "auto",
    ): String? = resolveText(sourceText, translatedText, sourceLanguage).text
}
