package io.translatetube.local.subs

/** Splits two-line imported bilingual cues without rewriting user-supplied subtitle wording. */
internal data class ImportedSubtitleText(
    val originalText: String,
    val translationText: String,
)

internal object ImportedSubtitlePolicy {
    private val chinesePattern = Regex("[\\u4e00-\\u9fff]")
    private val japaneseKanaPattern = Regex("[\\u3040-\\u30ff\\u31f0-\\u31ff]")

    fun splitBilingualCue(rawText: String): ImportedSubtitleText {
        val parts = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            val fallback = rawText.trim()
            return ImportedSubtitleText(fallback, fallback)
        }

        val matchingChineseRow = parts.indexOfLast {
            chinesePattern.containsMatchIn(it) && !japaneseKanaPattern.containsMatchIn(it)
        }
        val translationIndex = if (matchingChineseRow >= 0) matchingChineseRow else parts.lastIndex
        val translation = parts[translationIndex]
        // Remove only the selected translation row; filter-by-value would also drop an identical
        // original row. Imported captions are user data, so do not run generated-translation cleanup.
        val original = parts
            .filterIndexed { index, _ -> index != translationIndex }
            .joinToString("\n")
            .ifBlank { translation }
        return ImportedSubtitleText(original, translation)
    }
}
