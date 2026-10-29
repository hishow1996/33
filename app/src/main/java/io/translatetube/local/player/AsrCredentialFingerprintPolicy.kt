package io.translatetube.local.player

import java.security.MessageDigest
import java.util.Locale

/**
 * Canonicalizes saved encrypted engine credentials before they participate in config fingerprints.
 *
 * AndroidKeyStore AES/GCM uses a randomized IV, so encrypting the same API key twice creates
 * different preference strings. ASR keys and the built-in Gemini/DeepSeek keys must therefore be
 * compared by their decrypted value, then hashed without storing the raw credential in the identity.
 */
internal object AsrCredentialFingerprintPolicy {
    private const val ENCRYPTED_PREFIX = "TTENC1:"

    fun canonicalValue(
        key: String,
        storedValue: String,
        decrypt: (String) -> String,
    ): String {
        if (!isCredentialKey(key)) return storedValue
        val clearValue = runCatching { decrypt(storedValue) }.getOrNull()
        // Crypto.decrypt returns an empty string if an encrypted value can no longer be opened
        // (for example, after a keystore reset). Keep distinct corrupt ciphertexts isolated rather
        // than making every unreadable credential share the fingerprint of an empty key.
        val digestInput = if (
            clearValue.isNullOrEmpty() && storedValue.startsWith(ENCRYPTED_PREFIX)
        ) {
            "unreadable-ciphertext:" + storedValue
        } else {
            "credential:" + clearValue.orEmpty()
        }
        return "sha256:" + sha256(digestInput)
    }

    private fun isCredentialKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return (normalized.startsWith("asr_") && normalized.endsWith("_key")) ||
            normalized in setOf("free_gemini_key", "free_deepseek_key")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
}
