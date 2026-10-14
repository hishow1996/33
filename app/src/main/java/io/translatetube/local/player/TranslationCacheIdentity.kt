package io.translatetube.local.player

import io.translatetube.local.subs.SubLine

/** Stable source identity for imported subtitle translations, independent from Android APIs. */
internal object TranslationCacheIdentity {
    fun importedKey(key: String, activeId: Long, lines: List<SubLine>): String {
        val canonical = buildString {
            lines.forEach { line ->
                append(line.startMs).append(':')
                    .append(line.endMs).append(':')
                    .append(line.text.length).append(':')
                    .append(line.text).append('\n')
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val signature = digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$key|zh|$activeId|imported-$signature"
    }

    /**
     * Keeps the source fingerprint for imported subtitles while assigning the cache to the
     * provider actually selected for this processing session. Ordinary resume keys are rebuilt
     * from baseKey so a retry cannot overwrite another provider's translation cache.
     */
    fun forProvider(
        baseKey: String,
        activeId: Long,
        importedLines: List<SubLine>?,
        previousKey: String? = null,
    ): String {
        if (!importedLines.isNullOrEmpty()) return importedKey(baseKey, activeId, importedLines)

        val previous = previousKey?.takeIf { it.contains("|imported-") }
            ?: return "$baseKey|zh|$activeId"
        val marker = "|imported-"
        val markerIndex = previous.lastIndexOf(marker)
        val hash = previous.substring(markerIndex + marker.length)
        val zhIndex = previous.lastIndexOf("|zh|", markerIndex)
        if (zhIndex <= 0 || hash.length != 24 || hash.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return "$baseKey|zh|$activeId"
        }
        val stableSourceKey = previous.substring(0, zhIndex)
        return "$stableSourceKey|zh|$activeId|imported-$hash"
    }
}
