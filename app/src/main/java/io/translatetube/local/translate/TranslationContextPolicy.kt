package io.translatetube.local.translate

/** Finds a small, ordered tail of usable translated rows without copying the whole prefix. */
internal object TranslationContextPolicy {
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

}
