package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleLanguageDetector
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.subs.SubLine
import io.translatetube.local.translate.SubtitleTranslationQualityPolicy

internal data class DubbingTranslationPreparation(
    val lines: List<SubLine>,
    val originalAudioFallbackIndices: List<Int>,
    val fallbackReasons: Map<Int, String>,
)

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
        if (SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                sourceText, cleaned, detectedSource, "zh",
            )
        ) {
            return DubbingTranslationResolution(null, "译文未达到中文目标语言")
        }
        return DubbingTranslationResolution(cleaned)
    }

    /**
     * Reconcile translation quality with the actual dubbing preflight before persisting READY state.
     * A cue that cannot become Chinese speech is explicitly marked for original-audio fallback;
     * genuinely missing rows are still handled by the translation retry stage before reaching here.
     */
    fun prepareLinesForDubbing(
        lines: List<SubLine>,
        sourceLanguage: String = "auto",
    ): DubbingTranslationPreparation {
        val fallbackIndices = ArrayList<Int>()
        val fallbackReasons = linkedMapOf<Int, String>()
        val prepared = lines.mapIndexed { index, line ->
            if (line.text.isBlank()) return@mapIndexed line
            if (line.translationFallbackOriginalAudio) {
                fallbackIndices += index
                fallbackReasons[index] = line.translationFallbackReason
                    ?: "已由翻译阶段明确采用原声兜底（旧缓存未保存具体失败原因）"
                return@mapIndexed line.copy(zh = null)
            }

            val resolution = resolveText(line.text, line.zh, sourceLanguage)
            val speakable = resolution.text?.takeIf {
                io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(it)
            }
            if (speakable.isNullOrBlank()) {
                fallbackIndices += index
                val reason = resolution.reason
                    ?: "译文只有标点、符号或表情，没有可朗读内容"
                fallbackReasons[index] = reason
                line.copy(
                    zh = null,
                    translationFallbackOriginalAudio = true,
                    translationFallbackReason = reason,
                )
            } else {
                line.copy(zh = speakable, translationFallbackReason = null)
            }
        }
        return DubbingTranslationPreparation(
            lines = prepared,
            originalAudioFallbackIndices = fallbackIndices,
            fallbackReasons = fallbackReasons,
        )
    }

    fun usableText(
        sourceText: String,
        translatedText: String?,
        sourceLanguage: String = "auto",
    ): String? = resolveText(sourceText, translatedText, sourceLanguage).text
}
