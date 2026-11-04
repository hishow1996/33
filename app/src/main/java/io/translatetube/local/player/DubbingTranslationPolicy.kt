package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleLanguageDetector
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.subs.SubLine
import io.translatetube.local.translate.SubtitleTranslationQualityPolicy

/** Result of checking whether a subtitle can become Chinese speech. */
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
        val ambiguousHanSource = !hasLanguageHint &&
            SubtitleLanguageDetector.isHanDominantAmbiguous(sourceText)
        if (chineseSource) {
            // Preserve literal Chinese captions even if their text resembles a UI progress label.
            val literal = sourceText.trim()
            if (!io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(literal)) {
                return DubbingTranslationResolution(null, "原文字幕只有标点、符号或表情，没有可朗读内容")
            }
            return DubbingTranslationResolution(literal)
        }
        if (translatedText.isNullOrBlank()) {
            return DubbingTranslationResolution(
                null,
                if (ambiguousHanSource) {
                    "原字幕仅含汉字，无法确认是中文还是日语；请在翻译设置中确认源语言，或提供中文译文"
                } else {
                    "翻译结果为空或只有空白字符"
                },
            )
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
        if (!io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(cleaned)) {
            return DubbingTranslationResolution(
                null,
                "译文只有标点、符号或表情，没有可朗读内容",
            )
        }
        // 人名/品牌/外来词这类短行翻译后仍是拉丁字母写法：没有可翻内容，作为有效译文保留，
        // 不能让一行本来就不用翻译的字幕卡住整个视频的翻译和配音。
        if (SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(
                sourceText, cleaned, detectedSource,
            )
        ) {
            return DubbingTranslationResolution(cleaned)
        }
        if (SubtitleTranslationQualityPolicy.isSourceEcho(sourceText, cleaned, detectedSource)) {
            return DubbingTranslationResolution(
                null,
                if (ambiguousHanSource) {
                    "原字幕仅含汉字，无法确认是中文还是日语；拒绝把原文直接当成中文配音，请确认源语言或提供中文译文"
                } else {
                    "译文与原文相同，未生成中文译文"
                },
            )
        }
        if (SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                sourceText, cleaned, detectedSource, "zh",
            )
        ) {
            return DubbingTranslationResolution(null, "译文未达到中文目标语言")
        }
        return DubbingTranslationResolution(cleaned)
    }

    /**
     * Require every nonblank source cue to have usable Chinese speech.
     * Missing or invalid translations fail processing instead of switching that cue to source audio.
     */
    fun prepareLinesForDubbing(
        lines: List<SubLine>,
        sourceLanguage: String = "auto",
    ): List<SubLine> =
        lines.mapIndexed { index, line ->
            if (line.text.isBlank()) return@mapIndexed line
            // 只有 ♪、…、- 这类符号的原文没有可朗读内容，保持无译文，不当作翻译失败。
            if (SubtitleTranslationQualityPolicy.hasNothingToSpeak(line.text)) return@mapIndexed line
            val resolution = resolveText(line.text, line.zh, sourceLanguage)
            val speakable = resolution.text?.takeIf {
                io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(it)
            }
            if (speakable.isNullOrBlank()) {
                val reason = resolution.reason ?: "译文只有标点、符号或表情，没有可朗读内容"
                throw IllegalStateException("第 ${index + 1} 行字幕无法生成中文配音：$reason")
            }
            line.copy(zh = speakable)
        }

    fun usableText(
        sourceText: String,
        translatedText: String?,
        sourceLanguage: String = "auto",
    ): String? = resolveText(sourceText, translatedText, sourceLanguage).text
}
