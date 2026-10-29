package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsCacheIdentityPolicyTest {
    @Test
    fun sameVoiceAndCredentialProduceStableCacheKey() {
        assertEquals(
            TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a"),
            TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a"),
        )
    }

    @Test
    fun changedCredentialsAndVoiceCannotReuseGeminiPcmCache() {
        val original = TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a")
        assertFalse(original == TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-b"))
        assertFalse(original == TtsCacheIdentityPolicy.geminiCacheKey("Puck", "secret-a"))
    }

    @Test
    fun cacheKeyDoesNotContainTheRawApiKey() {
        val key = TtsCacheIdentityPolicy.geminiCacheKey("Kore", "AIza-example-secret")
        assertFalse(key.contains("AIza-example-secret"))
        assertTrue(key.startsWith("gemini-tts-v3-kore-"))
    }

    @Test
    fun missingCredentialsHaveAStableNonSecretMarker() {
        assertEquals("missing", TtsCacheIdentityPolicy.credentialFingerprint(""))
        assertEquals("missing", TtsCacheIdentityPolicy.credentialFingerprint("  "))
    }
}
