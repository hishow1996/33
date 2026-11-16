package io.translatetube.local.player

import io.translatetube.local.asr.WhisperOutputModePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrTranscriptCacheIdentityTest {
    private fun key(
        provider: String = "local",
        model: String = "whisper-base",
        task: String = WhisperOutputModePolicy.TRANSCRIBE,
    ) = AsrTranscriptCacheIdentity.key(provider, model, whisperTask = task)

    @Test
    fun sameSelectedEngineAndOutputModeAlwaysProducesTheSameTranscriptCacheIdentity() {
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
        assertNotEquals(AsrTranscriptCacheIdentity.key("cartesia", "unused"), AsrTranscriptCacheIdentity.key("kawa", "unused"))
    }

    @Test
    fun whisperOutputModesNeverReuseEachOthersTranscriptCache() {
        assertNotEquals(key(task = WhisperOutputModePolicy.TRANSCRIBE), key(task = WhisperOutputModePolicy.TRANSLATE_TO_ENGLISH))
        assertTrue("local-whisper-base-transcribe-engine-v2".startsWith(
            AsrTranscriptCacheIdentity.enginePrefix("local", "whisper-base", WhisperOutputModePolicy.TRANSCRIBE),
        ))
        assertTrue("local-whisper-base-translate-engine-v2".startsWith(
            AsrTranscriptCacheIdentity.enginePrefix("local", "whisper-base", WhisperOutputModePolicy.TRANSLATE_TO_ENGLISH),
        ))
    }

    @Test
    fun otherProvidersKeepTheirExistingIdentityFormat() {
        assertEquals("kawa-cloud-engine-v1", key(provider = "kawa", model = "unused"))
        assertEquals("local-sensevoice-engine-v1", key(model = "sensevoice"))
    }
}
