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
    /**
     * Valid translations that were held back only because an overlong row came before them, or
     * because the row itself was overlong. The caller keeps them as a last resort so a retry that
     * does not improve things can never leave a row emptier than it was.
     */
    val heldBackTranslations: Map<Int, String> = emptyMap(),
) {
    companion object {
        fun from(
            indices: List<Int>,
            rawTranslations: List<String>,
            sourceTexts: Map<Int, String> = emptyMap(),
            sourceLanguage: String = "auto",
            targetLanguage: String = "zh",
            /**
             * 重试后可放宽接受拼写近似的短专名；原文与译文完全一致且已通过专名校验的名称，
             * 首次返回即可保留。普通词语和短语不使用此例外。
             */
            acceptShortUnchanged: Boolean = false,
            /**
             * 译文比原文长得多，说明模型把后面几行的意思翻进了这一行，这一行之后的译文通常也整体错位。
             * 开启时：过长的行及其后的所有行都按未通过处理（同一次回复内），交给重试；
             * 重试的最后一次应关闭，保留模型的结果。
             */
            rejectOverlong: Boolean = false,
            /** 本视频已译行的正常「译文/原文」长度比；为空时按原文文字类型取默认值。 */
            overlongBaselineRatio: Double? = null,
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
            val heldBack = linkedMapOf<Int, String>()
            var firstOverlongPosition = -1
            indices.forEachIndexed { position, sourceIndex ->
                if (sourceIndex < 0) return@forEachIndexed
                val rawValue = rawTranslations.getOrNull(position)
                val value = SubtitleParsers.cleanTranslatedText(rawValue)
                val sourceText = sourceTexts[sourceIndex]
                if (!sourceText.isNullOrBlank() &&
                    SubtitleTranslationQualityPolicy.hasNothingToSpeak(sourceText)
                ) {
                    // A source cue made only of symbols has no speech to synthesize. Keep a
                    // speakable translated caption when supplied; otherwise preserve the source
                    // symbols as-is without treating the row as a translation failure.
                    if (value.isNullOrBlank()) {
                        // Empty model output: retain the original non-speech cue as-is.
                        if (translated[sourceIndex].isNullOrEmpty()) {
                            translated[sourceIndex] = sourceText
                        }
                        missing.remove(sourceIndex)
                        rejectionByIndex.remove(sourceIndex)
                        return@forEachIndexed
                    }
                    if (TtsTextPolicy.hasSpeakableContent(value)) {
                        if (translated[sourceIndex].isNullOrEmpty()) {
                            translated[sourceIndex] = value
                        }
                        missing.remove(sourceIndex)
                        rejectionByIndex.remove(sourceIndex)
                        return@forEachIndexed
                    }
                    missing += sourceIndex
                    rejectionByIndex.putIfAbsent(
                        sourceIndex,
                        TranslationRowRejection(
                            sourceIndex = sourceIndex,
                            reasonCode = "UNSPEAKABLE_TRANSLATION",
                            sourceCharCount = sourceText.length,
                            rawOutputCharCount = rawValue?.length ?: 0,
                            rawOutputPreview = rawValue.let {
                                TranslationFailureDiagnosticPolicy.safeSnippet(it, 120)
                            },
                            diagnosticReason = "译文只有标点、符号或表情，没有可朗读内容",
                        ),
                    )
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
                        val overlongMultiple = if (rejectOverlong && sourceIndex !in translated) {
                            SubtitleTranslationOverlongPolicy.overlongMultiple(
                                sourceText, acceptedValue, overlongBaselineRatio,
                            )
                        } else {
                            null
                        }
                        if (overlongMultiple != null) {
                            if (firstOverlongPosition < 0) firstOverlongPosition = position
                            heldBack[sourceIndex] = acceptedValue
                            missing += sourceIndex
                            rejectionByIndex.putIfAbsent(
                                sourceIndex,
                                TranslationRowRejection(
                                    sourceIndex = sourceIndex,
                                    reasonCode = "TRANSLATION_OVERLONG",
                                    sourceCharCount = sourceText?.length ?: 0,
                                    rawOutputCharCount = rawValue?.length ?: 0,
                                    rawOutputPreview = rawValue?.let {
                                        TranslationFailureDiagnosticPolicy.safeSnippet(it, 120)
                                    },
                                    diagnosticReason = "译文 ${SubtitleTranslationOverlongPolicy.units(acceptedValue)} 字，" +
                                        "原文 ${SubtitleTranslationOverlongPolicy.units(sourceText)} 字，" +
                                        "是本视频正常长度比的 ${String.format(java.util.Locale.ROOT, "%.1f", overlongMultiple)} 倍；" +
                                        "疑似把相邻行的内容翻进了这一行，这一行之后的译文可能整体错位",
                                ),
                            )
                        } else {
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
            }
            // A row that swallowed its neighbours shifts every later row of the same answer, so the
            // rows after it cannot be trusted either. Hold them back (the caller can still fall
            // back to them) and let the retry translate the whole tail again.
            var demotedAny = false
            if (firstOverlongPosition >= 0) {
                for (position in firstOverlongPosition + 1 until indices.size) {
                    val sourceIndex = indices[position]
                    if (sourceIndex < 0 || indices.indexOf(sourceIndex) != position) continue
                    val kept = translated.remove(sourceIndex) ?: continue
                    heldBack[sourceIndex] = kept
                    missing += sourceIndex
                    rejectionByIndex.putIfAbsent(
                        sourceIndex,
                        TranslationRowRejection(
                            sourceIndex = sourceIndex,
                            reasonCode = "AFTER_OVERLONG_ROW",
                            sourceCharCount = sourceTexts[sourceIndex]?.length ?: 0,
                            rawOutputCharCount = kept.length,
                            diagnosticReason = "位于过长译文行之后；该行译文本身有效，但这一批回复可能已整体错位，需要从过长行起一起重翻",
                        ),
                    )
                    demotedAny = true
                }
            }
            val orderedMissing = if (demotedAny) {
                indices.filter { it in missing }.distinct()
            } else {
                missing.toList()
            }
            return TranslationBatchResult(
                translations = translated,
                missingIndices = orderedMissing,
                rejectedRows = orderedMissing.mapNotNull(rejectionByIndex::get),
                heldBackTranslations = heldBack,
            )
        }
    }
}
