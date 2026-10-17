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
        // Hash cue-by-cue instead of building a second copy of the entire subtitle file in RAM.
        // Keep the exact canonical byte sequence so existing persisted cache keys remain compatible.
        val signature = importedLinesSignature(lines).take(IMPORTED_HASH_LENGTH)
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

    /**
     * Produces the same digest as hashing "$start:$end:$textLength:$text\\n" for every cue,
     * while avoiding one huge StringBuilder and matching toByteArray() allocation for long files.
     */
    private fun importedLinesSignature(lines: List<SubLine>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val colon = byteArrayOf(':'.code.toByte())
        val newline = byteArrayOf('\\n'.code.toByte())
        for (line in lines) {
            digest.update(line.startMs.toString().toByteArray(Charsets.UTF_8))
            digest.update(colon)
            digest.update(line.endMs.toString().toByteArray(Charsets.UTF_8))
            digest.update(colon)
            digest.update(line.text.length.toString().toByteArray(Charsets.UTF_8))
            digest.update(colon)
            digest.update(line.text.toByteArray(Charsets.UTF_8))
            digest.update(newline)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
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
