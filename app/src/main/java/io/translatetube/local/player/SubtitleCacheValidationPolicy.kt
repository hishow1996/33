package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.subs.SubLine
import io.translatetube.local.translate.SubtitleTranslationQualityPolicy

/** Structural guards for persisted subtitle timelines before binary-search playback uses them. */
internal object SubtitleCacheValidationPolicy {
    /** Match the ASR timeline bound and reject hostile cache sizes before allocating a hash set. */
    private const val MAX_CACHED_CUE_COUNT = 500_000

    data class CachedTranslationRow(
        val startMs: Long,
        val endMs: Long,
        val sourceText: String,
        val translatedText: String?,
    )

    /**
     * A cached translation made only of punctuation (for example "。") for a cue that contains
     * real words is a failed translation. Treat it as missing so only that row is retried.
     */
    private fun usableCachedTranslation(
        sourceText: String,
        cached: String?,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        val cleaned = SubtitleParsers.cleanTranslatedText(cached) ?: return null
        if (!SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                sourceText = sourceText,
                translatedText = cleaned,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                acceptShortUnchanged = true,
            )
        ) return null
        return cleaned
    }

    /**
     * Persist only translations accepted by the same language and speechability checks used by dubbing.
     * Invalid rows retain their source cue and timing, but their cached translation is cleared so
     * reopening the video retries only those rows.
     */
    fun sanitizeTranslationForStorage(line: SubLine, sourceLanguage: String): SubLine =
        line.copy(
            zh = if (line.text.isBlank()) null else
                DubbingTranslationPolicy.usableText(line.text, line.zh, sourceLanguage),
        )

    /** Nonempty but invalid echoes/placeholders do not make a translation cache recoverable. */
    fun hasRecoverableTranslationRows(
        rows: List<CachedTranslationRow>,
        sourceLanguage: String,
        targetLanguage: String = "zh",
    ): Boolean = rows.any { row ->
        usableCachedTranslation(row.sourceText, row.translatedText, sourceLanguage, targetLanguage) != null
    }

    /**
     * Restore an imported translation cache only when every cached cue still maps to the exact
     * source text and timeline. Partial valid caches are allowed; missing/placeholder rows remain
     * null so translateLines can retry just those rows.
     */
    fun restoreTranslationsForSource(
        sourceLines: List<SubLine>,
        cachedRows: List<CachedTranslationRow>,
        sourceLanguage: String = "auto",
        targetLanguage: String = "zh",
        checkActive: () -> Unit = {},
    ): List<SubLine>? {
        checkActive()
        if (sourceLines.isEmpty() || sourceLines.size != cachedRows.size ||
            sourceLines.size > MAX_CACHED_CUE_COUNT
        ) return null

        // Validate in one pass instead of materializing separate full-size timeline and translation
        // lists. Large subtitle caches can contain hundreds of thousands of rows on mobile devices.
        var previousStart = -1L
        var hasRecoverableTranslation = false
        val uniqueRows = HashSet<Triple<Long, Long, String>>(cachedRows.size)
        for (index in cachedRows.indices) {
            if ((index and 0xff) == 0) checkActive()
            val cached = cachedRows[index]
            val source = sourceLines[index]
            if (
                cached.startMs < 0L ||
                cached.endMs <= cached.startMs ||
                cached.sourceText.isBlank() ||
                cached.startMs < previousStart ||
                !uniqueRows.add(Triple(cached.startMs, cached.endMs, cached.sourceText)) ||
                source.startMs != cached.startMs ||
                source.endMs != cached.endMs ||
                source.text != cached.sourceText
            ) return null
            previousStart = cached.startMs
            if (usableCachedTranslation(
                    source.text, cached.translatedText, sourceLanguage, targetLanguage,
                ) != null
            ) {
                hasRecoverableTranslation = true
            }
        }
        if (!hasRecoverableTranslation) return null

        val restored = ArrayList<SubLine>(sourceLines.size)
        for (index in sourceLines.indices) {
            if ((index and 0xff) == 0) checkActive()
            val source = sourceLines[index]
            restored += source.copy(
                zh = usableCachedTranslation(
                    source.text, cachedRows[index].translatedText, sourceLanguage, targetLanguage,
                ),
            )
        }
        checkActive()
        return restored
    }

    /**
     * Keep original cue times intact, but reject malformed or out-of-order rows. Equal start times
     * are valid because captions may overlap; descending starts violate the playback index contract.
     */
    fun hasValidTimeline(rows: List<Triple<Long, Long, String>>): Boolean {
        if (rows.isEmpty() || rows.size > MAX_CACHED_CUE_COUNT) return false
        var previousStart = -1L
        val uniqueRows = HashSet<Triple<Long, Long, String>>(rows.size)
        for ((startMs, endMs, text) in rows) {
            if (startMs < 0L || endMs <= startMs || text.isBlank() || startMs < previousStart ||
                !uniqueRows.add(Triple(startMs, endMs, text))
            ) {
                return false
            }
            previousStart = startMs
        }
        return true
    }

    /**
     * A mixed cache can contain good translations beside interrupted progress placeholders.
     * Reuse it when at least one real translation survives cleanup; callers can then retry only
     * the blank/placeholder rows. A cache made entirely of placeholders is not recoverable.
     */
    fun hasRecoverableTranslationRows(translations: List<String?>): Boolean =
        translations.any { !SubtitleParsers.cleanTranslatedText(it).isNullOrBlank() }

    /**
     * Chinese source captions are already the target language. Repair legacy/incomplete cache rows
     * from their source text rather than leaving blank or stale placeholder translations visible.
     */
    fun restoreChineseSourceTranslations(rows: List<SubLine>): List<SubLine> =
        rows.map {
            it.copy(
                zh = it.text.takeIf { text -> text.isNotBlank() },
            )
        }
}
