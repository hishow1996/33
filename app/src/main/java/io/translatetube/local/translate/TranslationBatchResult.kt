package io.translatetube.local.translate

import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.tts.TtsTextPolicy

/** Structured, content-free reason for rejecting one model output row. */
internal data class TranslationRowRejection(
    val sourceIndex: Int,
    val reasonCode: String,
    val sourceCharCount: Int,
    val rawOutputCharCount: Int,
    val diagnosticReason: String? = null,
    /** Bounded local-only excerpt of rejected model output for diagnosis; never a full response. */
    val rawOutputPreview: String? = null,
    /** Content-free metrics from the exact subtitle quality checks used for this row. */
    val qualityEvidence: String? = null,
)

/**
 * Maps model output back to requested source-row indices without accepting progress/status
 * strings as subtitle translations. Short or misaligned output is represented as missing rows
 * so the caller can retry those rows only.
 */
internal data class TranslationBatchResult(
    val translations: Map<Int, String>,
    val missingIndices: List<Int>,
    /** Per-row reasons for rows rejected by subtitle quality/alignment validation. */
    val rejectedRows: List<TranslationRowRejection> = emptyList(),
) {
    companion object {
        fun from(
            indices: List<Int>,
            rawTranslations: List<String>,
            sourceTexts: Map<Int, String> = emptyMap(),
            sourceLanguage: String = "auto",
            targetLanguage: String = "zh",
        ): TranslationBatchResult {
            // Positional output is safe only when every requested cue has exactly one result.
            // A short/long response has no reliable row mapping, so retry the complete set.
            if (rawTranslations.size != indices.size) {
                val missing = indices.asSequence().filter { it >= 0 }.distinct().toList()
                return TranslationBatchResult(
                    translations = emptyMap(),
                    missingIndices = missing,
                    rejectedRows = missing.map { sourceIndex ->
                        TranslationRowRejection(
                            sourceIndex = sourceIndex,
                            reasonCode = "ROW_COUNT_MISMATCH",
                            sourceCharCount = sourceTexts[sourceIndex]?.length ?: 0,
                            rawOutputCharCount = 0,
                            diagnosticReason = "请求 ${indices.size} 行，但模型返回 ${rawTranslations.size} 行；返回数量不匹配，无法安全按位置对应字幕",
                        )
                    },
                )
            }
            val translated = linkedMapOf<Int, String>()
            val missing = linkedSetOf<Int>()
            val rejectionByIndex = linkedMapOf<Int, TranslationRowRejection>()
            indices.forEachIndexed { position, sourceIndex ->
                if (sourceIndex < 0) return@forEachIndexed
                val rawValue = rawTranslations.getOrNull(position)
                val value = SubtitleParsers.cleanTranslatedText(rawValue)
                val sourceText = sourceTexts[sourceIndex]
                val sourceEcho = value?.let {
                    SubtitleTranslationQualityPolicy.isSourceEcho(sourceText, it, sourceLanguage)
                } == true
                val targetMismatchDetail = value?.let {
                    SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                        sourceText, it, sourceLanguage, targetLanguage,
                    )
                }
                val targetMismatch = targetMismatchDetail != null
                val unspeakable = !value.isNullOrBlank() && !TtsTextPolicy.hasSpeakableContent(value)
                // 纯语气词（Mm-hmm / Hmm / Oh）机器翻译常原样返回或只给标点；译文失败时用固定中文语气词，
                // 确保这一句也有中文字幕和配音。
                val interjection = if (value.isNullOrBlank() || sourceEcho || targetMismatch || unspeakable) {
                    SubtitleInterjectionPolicy.chineseFor(sourceText, sourceLanguage, targetLanguage)
                } else {
                    null
                }
                if (interjection != null) {
                    if (translated[sourceIndex].isNullOrEmpty()) translated[sourceIndex] = interjection
                    missing.remove(sourceIndex)
                    rejectionByIndex.remove(sourceIndex)
                    return@forEachIndexed
                }
                val rejectionReason = when {
                    value.isNullOrBlank() -> "EMPTY_OR_STATUS_PLACEHOLDER"
                    unspeakable -> "UNSPEAKABLE_TRANSLATION"
                    sourceEcho && targetMismatch -> "SOURCE_ECHO_AND_TARGET_LANGUAGE_MISMATCH"
                    targetMismatch -> "TARGET_LANGUAGE_MISMATCH"
                    sourceEcho -> "SOURCE_ECHO"
                    else -> null
                }
                when {
                    value.isNullOrBlank() || targetMismatch || unspeakable -> {
                        // Empty output and status text still need another attempt.
                        if (sourceIndex !in translated) {
                            missing += sourceIndex
                            rejectionReason?.let { reason ->
                                rejectionByIndex.putIfAbsent(
                                    sourceIndex,
                                    TranslationRowRejection(
                                        sourceIndex = sourceIndex,
                                        reasonCode = reason,
                                        sourceCharCount = sourceText?.length ?: 0,
                                        rawOutputCharCount = rawValue?.length ?: 0,
                                        rawOutputPreview = rawValue?.let { TranslationFailureDiagnosticPolicy.safeSnippet(it, 120) },
                                        qualityEvidence = SubtitleTranslationQualityPolicy.diagnosticEvidence(
                                            sourceText = sourceText,
                                            translatedText = value,
                                            sourceLanguage = sourceLanguage,
                                            targetLanguage = targetLanguage,
                                        ),
                                        diagnosticReason = when {
                                            value.isNullOrBlank() -> "译文清理后为空，或只包含正在翻译等状态占位内容"
                                            unspeakable -> "译文只有标点、符号或表情，没有可朗读内容"
                                            sourceEcho && targetMismatch -> "译文归一化后与原文相同；同时，$targetMismatchDetail"
                                            sourceEcho -> "忽略大小写、空白和标点后，模型译文与原文相同"
                                            targetMismatch -> targetMismatchDetail
                                            else -> null
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    sourceEcho -> {
                        // Other engines must retry an echoed source row instead of presenting it
                        // as Chinese.
                        if (sourceIndex !in translated) {
                            missing += sourceIndex
                            rejectionReason?.let { reason ->
                                rejectionByIndex.putIfAbsent(
                                    sourceIndex,
                                    TranslationRowRejection(
                                        sourceIndex = sourceIndex,
                                        reasonCode = reason,
                                        sourceCharCount = sourceText?.length ?: 0,
                                        rawOutputCharCount = rawValue?.length ?: 0,
                                        rawOutputPreview = rawValue?.let { TranslationFailureDiagnosticPolicy.safeSnippet(it, 120) },
                                        qualityEvidence = SubtitleTranslationQualityPolicy.diagnosticEvidence(
                                            sourceText = sourceText,
                                            translatedText = value,
                                            sourceLanguage = sourceLanguage,
                                            targetLanguage = targetLanguage,
                                        ),
                                        diagnosticReason = when {
                                            value.isNullOrBlank() -> "译文清理后为空，或只包含正在翻译等状态占位内容"
                                            unspeakable -> "译文只有标点、符号或表情，没有可朗读内容"
                                            sourceEcho && targetMismatch -> "译文归一化后与原文相同；同时，$targetMismatchDetail"
                                            sourceEcho -> "忽略大小写、空白和标点后，模型译文与原文相同"
                                            targetMismatch -> targetMismatchDetail
                                            else -> null
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    else -> {
                        // Keep the first accepted translation for duplicate indices; a later
                        // rejected or blank output must never erase it.
                        if (translated[sourceIndex].isNullOrEmpty()) {
                            translated[sourceIndex] = value
                        } else {
                            translated.putIfAbsent(sourceIndex, value)
                        }
                        missing.remove(sourceIndex)
                        rejectionByIndex.remove(sourceIndex)
                    }
                }
            }
            return TranslationBatchResult(
                translations = translated,
                missingIndices = missing.toList(),
                rejectedRows = missing.mapNotNull(rejectionByIndex::get),
            )
        }
    }
}
