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
        sourceLanguage: String = "auto",
    ): String {
        val canonical = buildString {
            append("lang=").append(normalizeLanguage(sourceLanguage)).append('\n')
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

    fun matchesProfile(
        cacheKey: String,
        key: String,
        activeId: Long,
        profileIdentity: String,
    ): Boolean {
        val expected = videoKey(key, activeId, profileIdentity)
        return cacheKey == expected || cacheKey.startsWith("$expected|imported-")
    }

    /** Language tags are case-insensitive, but different regional tags are kept distinct. */
    fun sameSourceLanguage(cachedLanguage: String, currentLanguage: String): Boolean {
        val cached = normalizeLanguage(cachedLanguage)
        val current = normalizeLanguage(currentLanguage)
        return cached.isNotBlank() && current.isNotBlank() && cached == current
    }

    private fun normalizeLanguage(language: String): String =
        language.trim().replace('_', '-').lowercase()

    private fun fingerprint(value: String, bytesToKeep: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(bytesToKeep).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
