package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import java.security.MessageDigest
import java.util.Locale

/** Stable source/provider identity for persisted subtitle translations. */
internal object TranslationCacheIdentity {
    private const val IMPORTED_HASH_LENGTH = 24
    private const val PROVIDER_HASH_LENGTH = 24

    fun providerFingerprint(
        activeId: Long,
        providerKind: String,
        type: String = "",
        baseUrl: String = "",
        apiKey: String = "",
        model: String = "",
        region: String = "",
    ): String {
        // API keys may be encrypted in Room with a randomized IV. Hash the stored value before
        // folding it into this fingerprint so credential text is never persisted in a cache key.
        val credentialDigest = sha256(apiKey)
        val canonical = listOf(
            activeId.toString(),
            providerKind.trim().lowercase(Locale.ROOT),
            type.trim().uppercase(Locale.ROOT),
            baseUrl.trim().trimEnd('/'),
            credentialDigest,
            model.trim(),
            region.trim().lowercase(Locale.ROOT),
        ).joinToString("\n")
        return sha256(canonical).take(PROVIDER_HASH_LENGTH)
    }

    fun importedKey(
        key: String,
        activeId: Long,
        lines: List<SubLine>,
        providerFingerprint: String? = null,
    ): String {
        val canonical = buildString {
            lines.forEach { line ->
                append(line.startMs).append(':')
                    .append(line.endMs).append(':')
                    .append(line.text.length).append(':')
                    .append(line.text).append('\n')
            }
        }
        val signature = sha256(canonical).take(IMPORTED_HASH_LENGTH)
        return "$key|zh|$activeId${providerSuffix(providerFingerprint)}|imported-$signature"
    }

    /**
     * Keeps the imported source fingerprint on retry while refreshing the selected provider
     * identity. Ordinary resume keys include the new fingerprint so edits to a provider with a
     * stable database ID cannot accidentally reuse translations from its previous configuration.
     */
    fun forProvider(
        baseKey: String,
        activeId: Long,
        importedLines: List<SubLine>?,
        previousKey: String? = null,
        providerFingerprint: String? = null,
    ): String {
        if (!importedLines.isNullOrEmpty()) {
            return importedKey(baseKey, activeId, importedLines, providerFingerprint)
        }

        val suffix = providerSuffix(providerFingerprint)
        val previous = previousKey?.takeIf { it.contains("|imported-") }
            ?: return "$baseKey|zh|$activeId$suffix"

        val marker = "|imported-"
        val markerIndex = previous.lastIndexOf(marker)
        val hash = previous.substring(markerIndex + marker.length)
        val zhIndex = previous.lastIndexOf("|zh|", markerIndex)
        if (
            zhIndex <= 0 ||
            hash.length != IMPORTED_HASH_LENGTH ||
            hash.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) {
            return "$baseKey|zh|$activeId$suffix"
        }
        val stableSourceKey = previous.substring(0, zhIndex)
        return "$stableSourceKey|zh|$activeId$suffix|imported-$hash"
    }

    private fun providerSuffix(value: String?): String {
        val fingerprint = value?.takeIf {
            it.length == PROVIDER_HASH_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
        } ?: return ""
        return "|provider-$fingerprint"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
