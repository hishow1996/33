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
            /**
             * 重试之后才打开：1~3 个词的人名/品牌/外来词，引擎反复原样返回时当作有效结果，
             * 避免一个本来就不用翻译的短行让整段字幕翻译失败。首次返回仍按失败处理，让引擎有机会真的翻成中文。
             */
            acceptShortUnchanged: Boolean = false,
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
                if (!sourceText.isNullOrBlank() &&
                    SubtitleTranslationQualityPolicy.hasNothingToSpeak(sourceText)
                ) {
                    // A symbol-only source cue has no speech to produce. Do not cache a symbol-only
                    // model echo as a completed translation; keep it visible as a rejected row.
                    val hasSpeakableOutput = !value.isNullOrBlank() &&
                        TtsTextPolicy.hasSpeakableContent(value)
                    if (!hasSpeakableOutput) {
                        missing += sourceIndex
                        val reasonCode = if (value.isNullOrBlank()) {
                            "EMPTY_OR_STATUS_PLACEHOLDER"
                        } else {
                            "UNSPEAKABLE_TRANSLATION"
                        }
                        rejectionByIndex.putIfAbsent(
                            sourceIndex,
                            TranslationRowRejection(
                                sourceIndex = sourceIndex,
                                reasonCode = reasonCode,
                                sourceCharCount = sourceText.length,
                                rawOutputCharCount = rawValue?.length ?: 0,
                                rawOutputPreview = rawValue?.let {
                                    TranslationFailureDiagnosticPolicy.safeSnippet(it, 120)
                                },
                                diagnosticReason = if (value.isNullOrBlank()) {
                                    "模型未返回可用译文"
                                } else {
                                    "译文只有标点、符号或表情，没有可朗读内容"
                                },
                            ),
                        )
                    }
                    return@forEachIndexed
                }
                val qualityDecision = SubtitleTranslationQualityPolicy.evaluateTranslation(
                    sourceText = sourceText,
                    translatedText = value,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    acceptShortUnchanged = acceptShortUnchanged,
                )
                val sourceEcho = qualityDecision.sourceEcho
                val targetMismatchDetail = qualityDecision.targetLanguageMismatchReason
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
                val shortUntranslatedAccepted = qualityDecision.shortUntranslatedAccepted
                val rejectionReason = qualityDecision.reasonCode
                when {
                    shortUntranslatedAccepted -> {
                        if (translated[sourceIndex].isNullOrEmpty()) translated[sourceIndex] = value!!
                        missing.remove(sourceIndex)
                        rejectionByIndex.remove(sourceIndex)
                    }
                    !qualityDecision.accepted -> {
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
                                            decision = qualityDecision,
                                        ),
                                        diagnosticReason = qualityDecision.reason,
                                    ),
                                )
                            }
                        }
                    }
                    else -> {
                        // Keep the first accepted translation for duplicate indices; a later
                        // rejected or blank output must never erase it.
                        val acceptedValue = requireNotNull(value) {
                            "Accepted subtitle translation must not be null"
                        }
                        if (translated[sourceIndex].isNullOrEmpty()) {
                            translated[sourceIndex] = acceptedValue
                        } else {
                            translated.putIfAbsent(sourceIndex, acceptedValue)
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
