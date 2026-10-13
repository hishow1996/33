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
}
