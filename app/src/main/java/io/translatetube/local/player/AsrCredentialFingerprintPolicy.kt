package io.translatetube.local.player

import java.security.MessageDigest
import java.util.Locale

/**
 * Canonicalizes saved ASR credentials before they participate in config fingerprints.
 *
 * AndroidKeyStore AES/GCM uses a randomized IV, so encrypting the same API key twice creates
 * different preference strings. Hash the decrypted credential to keep the effective config
 * identity stable without putting the key itself in persisted fingerprints or cache names.
 */
internal object AsrCredentialFingerprintPolicy {
    private const val ENCRYPTED_PREFIX = "TTENC1:"

    fun canonicalValue(
        key: String,
        storedValue: String,
        decrypt: (String) -> String,
    ): String {
        if (!isAsrCredentialKey(key)) return storedValue
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

    private fun isAsrCredentialKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.startsWith("asr_") && normalized.endsWith("_key")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
}
