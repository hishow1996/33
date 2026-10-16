package io.translatetube.local.translate

import java.util.Locale

/** Shared validation for live translation batches and restored subtitle caches. */
internal object SubtitleTranslationQualityPolicy {
    private val trailingPunctuation = setOf(
        '.', ',', '!', '?', '。', '，', '！', '？', '；', ';', ':', '：', '…'
    )

    fun isSourceEcho(sourceText: String?, translatedText: String?, sourceLanguage: String): Boolean {
        if (sourceText.isNullOrBlank() || translatedText.isNullOrBlank()) return false
        val language = sourceLanguage.trim().lowercase(Locale.ROOT)
        if (language.startsWith("zh") || language == "cmn" || language.startsWith("yue")) return false
        return normalize(sourceText) == normalize(translatedText)
    }

    private fun normalize(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
            .trimEnd { it.isWhitespace() || it in trailingPunctuation }
}