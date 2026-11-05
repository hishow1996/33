package io.translatetube.local.translate

/** Finds a small, ordered tail of usable translated rows without copying the whole prefix. */
internal object TranslationContextPolicy {
    const val MAX_CONTEXT_ROWS = 8
    const val MAX_CONTEXT_JSON_CHARS = 6_000
    const val MAX_LOOKAHEAD_JSON_CHARS = 1_600
    private const val MAX_TERM_SCAN_ROWS = 1_024
    private const val MAX_TERM_REFERENCE_ROWS = 3

    /**
     * Return at most [maxRows] usable indexes before [beforeIndex], ordered oldest to newest.
     * Search walks backward and stops as soon as the requested tail is found.
     */
    fun mostRecentIndicesBefore(
        size: Int,
        beforeIndex: Int,
        maxRows: Int = 3,
        checkActive: () -> Unit = {},
        isUsable: (Int) -> Boolean,
    ): List<Int> {
        if (size <= 0 || maxRows <= 0) return emptyList()
        var cursor = beforeIndex.coerceIn(0, size) - 1
        if (cursor < 0) return emptyList()

        val result = ArrayList<Int>(minOf(maxRows, cursor + 1))
        var scanned = 0
        while (cursor >= 0 && result.size < maxRows) {
            if ((scanned++ and 0xff) == 0) checkActive()
            if (isUsable(cursor)) result += cursor
            cursor--
        }
        checkActive()
        result.reverse()
        return result
    }
    
    /**
     * Build a recent, chronological source/translation window before a cue.
     * Each pair is read together so retry results can replace stale cached values
     * without breaking source-to-translation alignment.
     */
    fun mostRecentPairsBefore(
        size: Int,
        beforeIndex: Int,
        maxRows: Int = 3,
        checkActive: () -> Unit = {},
        sourceAt: (Int) -> String?,
        translationAt: (Int) -> String?,
        isUsable: (source: String, translation: String) -> Boolean = { _, _ -> true },
    ): Pair<List<String>, List<String>>? {
        if (size <= 0 || maxRows <= 0) return null
        var cursor = beforeIndex.coerceIn(0, size) - 1
        if (cursor < 0) return null

        val pairs = ArrayList<Pair<String, String>>(minOf(maxRows, cursor + 1))
        var scanned = 0
        while (cursor >= 0 && pairs.size < maxRows) {
            if ((scanned++ and 0xff) == 0) checkActive()
            val source = sourceAt(cursor).orEmpty()
            val translation = translationAt(cursor).orEmpty()
            if (source.isNotBlank() && translation.isNotBlank() && isUsable(source, translation)) {
                pairs += source to translation
            }
            cursor--
        }
        checkActive()
        if (pairs.isEmpty()) return null
        pairs.reverse()
        return pairs.map { it.first } to pairs.map { it.second }
    }

    /**
     * Build a bounded context that combines the latest usable rows with older rows mentioning
     * source-grounded names or terms that recur in the current batch.
     *
     * Term references never replace current source text. Every accepted pair stays aligned;
     * scanning older rows is bounded and cancellation-aware for large subtitle timelines.
     */
    fun mostRecentPairsWithTermContinuityBefore(
        size: Int,
        beforeIndex: Int,
        currentSourceCues: List<String>,
        sourceLanguage: String = "auto",
        maxRows: Int = MAX_CONTEXT_ROWS,
        maxTermRows: Int = MAX_TERM_REFERENCE_ROWS,
        maxScanRows: Int = MAX_TERM_SCAN_ROWS,
        checkActive: () -> Unit = {},
        sourceAt: (Int) -> String?,
        translationAt: (Int) -> String?,
        isUsable: (source: String, translation: String) -> Boolean = { _, _ -> true },
    ): Pair<List<String>, List<String>>? {
        if (size <= 0 || maxRows <= 0) return null
        val boundary = beforeIndex.coerceIn(0, size)
        if (boundary <= 0) return null

        val resolvedSourceLanguage = SubtitleTermContinuityPolicy.resolveLanguage(currentSourceCues, sourceLanguage)
        val termKeys = SubtitleTermContinuityPolicy.extractStableTerms(currentSourceCues, resolvedSourceLanguage)
        val currentHasUncasedScript = currentSourceCues.any { cue ->
            cue.any { char ->
                when (Character.UnicodeScript.of(char.code)) {
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.HANGUL,
                    Character.UnicodeScript.ARABIC,
                    Character.UnicodeScript.HEBREW,
                    Character.UnicodeScript.DEVANAGARI -> true
                    Character.UnicodeScript.HAN -> resolvedSourceLanguage == "ja" ||
                        resolvedSourceLanguage == "auto"
                    else -> false
                }
            }
        }
        val recent = ArrayList<Triple<Int, String, String>>(minOf(maxRows, boundary))
        var cursor = boundary - 1
        var scanned = 0
        while (cursor >= 0 && recent.size < maxRows && scanned < maxScanRows) {
            if ((scanned++ and 0xff) == 0) checkActive()
            val source = sourceAt(cursor).orEmpty()
            val translation = translationAt(cursor).orEmpty()
            if (source.isNotBlank() && translation.isNotBlank() && isUsable(source, translation)) {
                recent += Triple(cursor, source, translation)
            }
            cursor--
        }

        if ((termKeys.isEmpty() && !currentHasUncasedScript) ||
            maxTermRows <= 0 || maxScanRows <= 0 || cursor < 0
        ) {
            checkActive()
            if (recent.isEmpty()) return null
            val ordered = recent.asReversed()
            return ordered.map { it.second } to ordered.map { it.third }
        }

        val recentIndices = recent.mapTo(HashSet()) { it.first }
        val historicalTerms = ArrayList<Triple<Int, String, String>>(maxTermRows)
        val scanLimit = minOf(boundary, maxScanRows)
        while (cursor >= 0 && scanned < scanLimit && historicalTerms.size < maxTermRows) {
            if ((scanned++ and 0xff) == 0) checkActive()
            val source = sourceAt(cursor).orEmpty()
            val translation = translationAt(cursor).orEmpty()
            val matchesCurrentCasedOrMarkedTerm = termKeys.isNotEmpty() &&
                SubtitleTermContinuityPolicy.containsStableTerm(
                    source, termKeys, resolvedSourceLanguage,
                )
            val matchesUnmarkedUncasedName = currentHasUncasedScript &&
                SubtitleTermContinuityPolicy.extractHighConfidenceUncasedEntityTokens(source).any { term ->
                    currentSourceCues.any { cue ->
                        SubtitleTermContinuityPolicy.containsStableTerm(
                            cue, setOf(term), resolvedSourceLanguage,
                        )
                    }
                }
            if (cursor !in recentIndices && source.isNotBlank() && translation.isNotBlank() &&
                (matchesCurrentCasedOrMarkedTerm || matchesUnmarkedUncasedName) &&
                isUsable(source, translation)
            ) {
                historicalTerms += Triple(cursor, source, translation)
            }
            cursor--
        }
        checkActive()

        if (recent.isEmpty() && historicalTerms.isEmpty()) return null
        val termReferences = historicalTerms.asReversed()
        val recentCapacity = (maxRows - termReferences.size).coerceAtLeast(0)
        val selected = (termReferences + recent.take(recentCapacity))
            .distinctBy { it.first }
            .sortedBy { it.first }
            .takeLast(maxRows)
        if (selected.isEmpty()) return null
        return selected.map { it.second } to selected.map { it.third }
    }

    /**
     * Build the current batch's history window from an optional incoming context plus all
     * completed cues in this translation run. The history is accessed by index so long videos
     * do not require copying their full source/translation prefixes for every batch.
     */
    fun contextForBatchWithTermContinuity(
        initialContext: Pair<List<String>, List<String>>?,
        completedSources: List<String>,
        completedTranslations: List<String>,
        currentSourceCues: List<String>,
        sourceLanguage: String = "auto",
        maxRows: Int = MAX_CONTEXT_ROWS,
        maxTermRows: Int = MAX_TERM_REFERENCE_ROWS,
        maxScanRows: Int = MAX_TERM_SCAN_ROWS,
        checkActive: () -> Unit = {},
        isUsable: (source: String, translation: String) -> Boolean = { _, translation ->
            translation.isNotBlank()
        },
    ): Pair<List<String>, List<String>>? {
        val initialSources = initialContext?.first.orEmpty()
        val initialTranslations = initialContext?.second.orEmpty()
        val initialCount = minOf(initialSources.size, initialTranslations.size)
        val completedCount = minOf(completedSources.size, completedTranslations.size)
        val historySize = initialCount + completedCount
        if (historySize == 0) return null

        return mostRecentPairsWithTermContinuityBefore(
            size = historySize,
            beforeIndex = historySize,
            currentSourceCues = currentSourceCues,
            sourceLanguage = sourceLanguage,
            maxRows = maxRows,
            maxTermRows = maxTermRows,
            maxScanRows = maxScanRows,
            checkActive = checkActive,
            sourceAt = { index ->
                if (index < initialCount) initialSources[index]
                else completedSources[index - initialCount]
            },
            translationAt = { index ->
                if (index < initialCount) initialTranslations[index]
                else completedTranslations[index - initialCount]
            },
            isUsable = isUsable,
        )
    }

}
