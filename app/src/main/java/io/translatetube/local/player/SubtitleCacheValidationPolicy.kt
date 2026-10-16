package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.subs.SubLine

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
     * Restore an imported translation cache only when every cached cue still maps to the exact
     * source text and timeline. Partial valid caches are allowed; missing/placeholder rows remain
     * null so translateLines can retry just those rows.
     */
    fun restoreTranslationsForSource(
        sourceLines: List<SubLine>,
        cachedRows: List<CachedTranslationRow>,
    ): List<SubLine>? {
        if (sourceLines.isEmpty() || sourceLines.size != cachedRows.size ||
            sourceLines.size > MAX_CACHED_CUE_COUNT
        ) return null
        if (!hasValidTimeline(cachedRows.map { Triple(it.startMs, it.endMs, it.sourceText) })) return null
        if (!hasRecoverableTranslationRows(cachedRows.map { it.translatedText })) return null
        if (sourceLines.indices.any { index ->
            val source = sourceLines[index]
            val cached = cachedRows[index]
            source.startMs != cached.startMs ||
                source.endMs != cached.endMs ||
                source.text != cached.sourceText
        }) return null

        return sourceLines.mapIndexed { index, source ->
            source.copy(zh = SubtitleParsers.cleanTranslatedText(cachedRows[index].translatedText))
        }
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
        rows.map { line -> line.copy(zh = line.text.takeIf { it.isNotBlank() }) }
}
