package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrTranscriptCacheIdentityTest {
    private fun key(
        provider: String = "local",
        model: String = "whisper-base",
    ) = AsrTranscriptCacheIdentity.key(provider, model)

    @Test
    fun sameSelectedEngineAlwaysProducesTheSameTranscriptCacheIdentity() {
        assertEquals(key(), key())
        assertEquals(
            AsrTranscriptCacheIdentity.key("cartesia", "ignored-local-model"),
            AsrTranscriptCacheIdentity.key("cartesia", "another-local-model"),
        )
    }

    @Test
    fun changingProviderOrSelectedLocalModelSeparatesTranscriptCaches() {
        assertNotEquals(key(), key(model = "sensevoice"))
        assertNotEquals(key(), key(provider = "elevenlabs"))
        assertNotEquals(
            AsrTranscriptCacheIdentity.key("cartesia", "unused"),
            AsrTranscriptCacheIdentity.key("kawa", "unused"),
        )
    }

    @Test
    fun legacyCachePrefixMatchesKeysWithOldConfigurationSuffixes() {
        val prefix = AsrTranscriptCacheIdentity.enginePrefix("local", "whisper-base")
        assertTrue("local-whisper-base-auto-cfg-0123456789abcdef01234567".startsWith(prefix))
        assertTrue("local-whisper-base-engine-v1".startsWith(prefix))
        assertTrue(
            AsrTranscriptCacheIdentity.enginePrefix("cartesia", "unused")
                .let { "cartesia-cloud-en-cfg-old-digest".startsWith(it) },
        )
    }

    @Test
    fun cacheIdentityContainsOnlyTheSelectedEngineAndNoCredentials() {
        val identity = AsrTranscriptCacheIdentity.key("kawa", "private-api-secret-not-a-cache-identity")
        assertEquals("kawa-cloud-engine-v1", identity)
        assertEquals(
            "local-whisper-base-engine-v1",
            AsrTranscriptCacheIdentity.key("local", "whisper-base"),
        )
    }
}
