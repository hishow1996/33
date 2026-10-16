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
        fun from(
            indices: List<Int>,
            rawTranslations: List<String>,
            sourceTexts: Map<Int, String> = emptyMap(),
            sourceLanguage: String = "auto",
        ): TranslationBatchResult {
            val translated = linkedMapOf<Int, String>()
            val missing = linkedSetOf<Int>()
            indices.forEachIndexed { position, sourceIndex ->
                if (sourceIndex < 0) return@forEachIndexed
                val value = SubtitleParsers.cleanTranslatedText(rawTranslations.getOrNull(position))
                val sourceEcho = value?.let {
                    isUntranslatedEcho(sourceTexts[sourceIndex], it, sourceLanguage)
                } == true
                if (value.isNullOrBlank() || sourceEcho) {
                    // Empty output, status text, and source text returned as its own "translation"
                    // all need another attempt; never publish or cache these rows as Chinese.
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

        private fun isUntranslatedEcho(source: String?, translated: String, sourceLanguage: String): Boolean {
            if (source.isNullOrBlank() || translated.isBlank()) return false
            val language = sourceLanguage.trim().lowercase(java.util.Locale.ROOT)
            if (language.startsWith("zh") || language == "cmn" || language.startsWith("yue")) return false

            fun normalize(value: String): String =
                value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

            return normalize(source) == normalize(translated)
        }
    }
}
