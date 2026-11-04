package io.translatetube.local.translate

import io.translatetube.local.subs.SubtitleParsers

/**
 * Maps model output back to requested source-row indices without accepting progress/status
 * strings as subtitle translations. Short or misaligned output is represented as missing rows
 * so the caller can retry those rows only.
 */
internal data class TranslationRowRejection(
    val sourceIndex: Int,
    val reasonCode: String,
    val sourceCharCount: Int,
    val rawOutputCharCount: Int,
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
            /** Google/ML Kit may validly leave a name or untranslatable cue unchanged. */
            sourceEchoAsUntranslatedFallback: Boolean = false,
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
                val targetMismatch = value?.let {
                    SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                        sourceText, it, sourceLanguage, targetLanguage,
                    )
                } == true
                val rejectionReason = when {
                    value.isNullOrBlank() -> "EMPTY_OR_STATUS_PLACEHOLDER"
                    sourceEcho && targetMismatch -> "SOURCE_ECHO_AND_TARGET_LANGUAGE_MISMATCH"
                    targetMismatch -> "TARGET_LANGUAGE_MISMATCH"
                    sourceEcho && !sourceEchoAsUntranslatedFallback -> "SOURCE_ECHO"
                    else -> null
                }
                when {
                    value.isNullOrBlank() || targetMismatch -> {
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
                                    ),
                                )
                            }
                        }
                    }
                    sourceEcho && sourceEchoAsUntranslatedFallback -> {
                        // Keep an explicit empty translation for this cue: subtitles can still
                        // show the original text and DubbingTranslationPolicy will preserve its
                        // original audio rather than speaking the source with a Chinese voice.
                        if (sourceIndex !in translated) translated[sourceIndex] = ""
                        missing.remove(sourceIndex)
                        rejectionByIndex.remove(sourceIndex)
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
                                    ),
                                )
                            }
                        }
                    }
                    else -> {
                        // A real translation should replace a previous empty echo fallback, while
                        // duplicate indices must never let a later blank erase a valid translation.
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
