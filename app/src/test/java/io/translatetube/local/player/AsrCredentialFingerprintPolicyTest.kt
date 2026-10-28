package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AsrCredentialFingerprintPolicyTest {
    @Test
    fun sameDecryptedCredentialHasStableFingerprintAcrossRandomizedCiphertexts() {
        val decoded = mapOf(
            "TTENC1:random-iv-a" to "same-api-key",
            "TTENC1:random-iv-b" to "same-api-key",
        )
        val first = AsrCredentialFingerprintPolicy.canonicalValue(
            "asr_cartesia_key", "TTENC1:random-iv-a", decoded::getValue,
        )
        val second = AsrCredentialFingerprintPolicy.canonicalValue(
            "asr_cartesia_key", "TTENC1:random-iv-b", decoded::getValue,
        )
        assertEquals(first, second)
        assertFalse(first.contains("same-api-key"))
    }

    @Test
    fun changedCredentialChangesFingerprintWithoutExposingSecret() {
        val first = AsrCredentialFingerprintPolicy.canonicalValue(
            "asr_elevenlabs_key", "secret-a", { it },
        )
        val second = AsrCredentialFingerprintPolicy.canonicalValue(
            "asr_elevenlabs_key", "secret-b", { it },
        )
        assertNotEquals(first, second)
        assertFalse(first.contains("secret-a"))
        assertFalse(second.contains("secret-b"))
    }

    @Test
    fun unrelatedPreferencesAreLeftUntouched() {
        assertEquals(
            "raw-value",
            AsrCredentialFingerprintPolicy.canonicalValue("tts_engine", "raw-value") { it },
        )
        assertEquals(
            "raw-value",
            AsrCredentialFingerprintPolicy.canonicalValue("asr_provider", "raw-value") { it },
        )
    }

    @Test
    fun unreadableEncryptedCredentialsRemainIsolated() {
        val first = AsrCredentialFingerprintPolicy.canonicalValue(
            "asr_kawa_key", "TTENC1:bad-a", { "" },
        )
        val second = AsrCredentialFingerprintPolicy.canonicalValue(
            "asr_kawa_key", "TTENC1:bad-b", { "" },
        )
        assertNotEquals(first, second)
    }
}
