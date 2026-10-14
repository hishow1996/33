package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import java.security.MessageDigest

/**
 * Stable source identity for translation checkpoints. Profile configuration is hashed before it
 * enters a database key, so changing endpoint/model/key/region invalidates stale output without
 * storing the API credential text as part of the cache key.
 */
internal object TranslationCacheIdentity {
    fun profileToken(activeId: Long, profileIdentity: String): String =
        fingerprint("translation-profile-v2|$activeId|$profileIdentity", 10)

    fun videoKey(key: String, activeId: Long, profileIdentity: String): String =
        "$key|zh|$activeId|profile-${profileToken(activeId, profileIdentity)}"

    fun importedKey(
        key: String,
        activeId: Long,
        lines: List<SubLine>,
        profileIdentity: String = "",
    ): String {
        val canonical = buildString {
            lines.forEach { line ->
                append(line.startMs).append(':')
                    .append(line.endMs).append(':')
                    .append(line.text.length).append(':')
                    .append(line.text).append('\n')
            }
        }
        val sourceSignature = fingerprint(canonical, 12)
        return "${videoKey(key, activeId, profileIdentity)}|imported-$sourceSignature"
    }

    private fun fingerprint(value: String, bytesToKeep: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(bytesToKeep).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
