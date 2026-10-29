package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AsrTranscriptCacheIdentityTest {
    private fun key(
        provider: String = "local",
        model: String = "whisper-base",
        language: String = "auto",
        config: String = "0123456789abcdef01234567",
        modelFingerprint: String = "",
    ) = AsrTranscriptCacheIdentity.key(
        provider, model, language, config, modelFingerprint,
    )

    @Test
    fun identicalEffectiveConfigurationProducesStableIdentity() {
        assertEquals(key(), key())
    }

    @Test
    fun changingLocalModelOrProviderSeparatesTranscriptCaches() {
        assertNotEquals(key(), key(model = "sensevoice"))
        assertNotEquals(key(), key(provider = "elevenlabs"))
    }

    @Test
    fun changingCloudLanguageOrTranscriptionConfigurationSeparatesCaches() {
        val baseline = key(provider = "cartesia", language = "en")
        assertNotEquals(baseline, key(provider = "cartesia", language = "ja"))
        assertNotEquals(
            baseline,
            key(provider = "cartesia", language = "en", config = "fedcba9876543210fedcba98"),
        )
    }

    @Test
    fun localModelFileUpdateChangesTranscriptCacheIdentity() {
        assertNotEquals(
            key(modelFingerprint = "model-files-version-a"),
            key(modelFingerprint = "model-files-version-b"),
        )
    }

    @Test
    fun cloudCacheIdentityDoesNotDependOnLocalModelFiles() {
        assertEquals(
            key(provider = "cartesia", language = "en", modelFingerprint = ""),
            key(provider = "cartesia", language = "en", modelFingerprint = "local-files-version"),
        )
    }

    @Test
    fun rawCredentialsAreNeverCopiedIntoTheCacheIdentity() {
        val secret = "private-api-secret-not-a-fingerprint"
        val identity = key(provider = "kawa", config = secret)
        assertFalse(identity.contains(secret))
        assertFalse(identity.contains("private-api-secret"))
        assertEquals(24, identity.substringAfter("-cfg-").length)
    }
}
