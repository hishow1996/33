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
            /** Google/ML Kit may validly leave a name or untranslatable cue unchanged. */
            sourceEchoAsUntranslatedFallback: Boolean = false,
        ): TranslationBatchResult {
            val translated = linkedMapOf<Int, String>()
            val missing = linkedSetOf<Int>()
            indices.forEachIndexed { position, sourceIndex ->
                if (sourceIndex < 0) return@forEachIndexed
                val value = SubtitleParsers.cleanTranslatedText(rawTranslations.getOrNull(position))
                val sourceEcho = value?.let {
                    SubtitleTranslationQualityPolicy.isSourceEcho(sourceTexts[sourceIndex], it, sourceLanguage)
                } == true
                when {
                    value.isNullOrBlank() -> {
                        // Empty output and status text still need another attempt.
                        if (sourceIndex !in translated) missing += sourceIndex
                    }
                    sourceEcho && sourceEchoAsUntranslatedFallback -> {
                        // Keep an explicit empty translation for this cue: subtitles can still
                        // show the original text and DubbingTranslationPolicy will preserve its
                        // original audio rather than speaking the source with a Chinese voice.
                        if (sourceIndex !in translated) translated[sourceIndex] = ""
                        missing.remove(sourceIndex)
                    }
                    sourceEcho -> {
                        // Other engines must retry an echoed source row instead of presenting it
                        // as Chinese.
                        if (sourceIndex !in translated) missing += sourceIndex
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
                    }
                }
            }
            return TranslationBatchResult(translated, missing.toList())
        }
    }
}
