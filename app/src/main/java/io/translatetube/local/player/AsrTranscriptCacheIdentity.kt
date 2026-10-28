package io.translatetube.local.player

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable identity for ASR transcript and subtitle caches.
 *
 * The config fingerprint is a one-way digest of transcription-related preferences. Persist only
 * this digest, never raw API credentials or endpoint configuration, in cache keys.
 */
internal object AsrTranscriptCacheIdentity {
    private val configFingerprintPattern = Regex("^[0-9a-f]{24}$")

    fun key(
        provider: String,
        localModelId: String,
        language: String,
        configFingerprint: String,
    ): String {
        val normalizedProvider = token(provider)
        val model = if (normalizedProvider == "local") token(localModelId) else "cloud"
        val languagePart = if (normalizedProvider == "local") "auto" else token(language).ifBlank { "auto" }
        val configPart = configFingerprint.lowercase(Locale.ROOT)
            .takeIf(configFingerprintPattern::matches)
            ?: sha256(configFingerprint).take(24)
        return "$normalizedProvider-$model-$languagePart-cfg-$configPart"
    }

    private fun token(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "unknown" }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
}
