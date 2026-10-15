package io.translatetube.local.translate

import io.translatetube.local.subs.SubtitleParsers

/**
 * Maps model output back to requested source-row indices without accepting progress/status
 * strings as subtitle translations. Short or misaligned output is represented as missing rows
 * so the caller can retry those rows only.
 */
internal data class TranslationBatchResult(
    val translations: Map<Int, String>,
    val missingIndices: List<Int>,
) {
    companion object {
        fun from(indices: List<Int>, rawTranslations: List<String>): TranslationBatchResult {
            val translated = linkedMapOf<Int, String>()
            val missing = linkedSetOf<Int>()
            indices.forEachIndexed { position, sourceIndex ->
                val value = SubtitleParsers.cleanTranslatedText(rawTranslations.getOrNull(position))
                if (value.isNullOrBlank()) {
                    if (sourceIndex !in translated) missing += sourceIndex
                } else {
                    // Duplicate indices must not create duplicate retries or let a later blank
                    // result erase a valid translation for the same source row.
                    translated.putIfAbsent(sourceIndex, value)
                    missing.remove(sourceIndex)
                }
            }
            return TranslationBatchResult(translated, missing.toList())
        }
    }
}
