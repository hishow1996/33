package io.translatetube.local.subs

/**
 * Prepares imported translated captions for direct TTS. The uploaded timestamps are retained,
 * empty/invalid cues are discarded, Chinese rows in bilingual cues are preferred as the dub text,
 * and progress placeholders are never spoken as if they were a real translation.
 */
internal object TranslatedSubtitleImportPolicy {
    private val chinesePattern = Regex("[\\u4e00-\\u9fff]")

    fun prepare(input: List<SubLine>): List<SubLine> =
        input.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedWith(compareBy<SubLine> { it.startMs }.thenBy { it.index })
            .mapNotNull { line ->
                val parts = line.text.lines().map { it.trim() }.filter { it.isNotBlank() }
                val translation = parts.lastOrNull { chinesePattern.containsMatchIn(it) }
                    ?: parts.lastOrNull()
                    ?: return@mapNotNull null
                val cleanedTranslation = SubtitleParsers.cleanTranslatedText(translation)
                    ?: return@mapNotNull null
                val original = parts.filter { it != translation }.joinToString("\n").ifBlank { translation }
                line.copy(
                    text = original,
                    zh = cleanedTranslation,
                )
            }
            .mapIndexed { index, line -> line.copy(index = index) }
            .toList()
}
