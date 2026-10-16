package io.translatetube.local.player

import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.subs.SubLine

/** Structural guards for persisted subtitle timelines before binary-search playback uses them. */
internal object SubtitleCacheValidationPolicy {
    /**
     * Keep original cue times intact, but reject malformed or out-of-order rows. Equal start times
     * are valid because captions may overlap; descending starts violate the playback index contract.
     */
    fun hasValidTimeline(rows: List<Triple<Long, Long, String>>): Boolean {
        if (rows.isEmpty()) return false
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
