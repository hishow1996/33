package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrConfigPreferencePolicyTest {
    @Test
    fun selectedProviderAndLocalModelDefineTheLocalEngineIdentity() {
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_provider", "local"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_local_model", "local"))
    }

    @Test
    fun localRuntimeOptionsDoNotInvalidateAnExistingTranscript() {
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("local_asr_threads", "local"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("whisper_beam_size", "local"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("sensevoice_threads", "local"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("transcription_normalize", "local"))
    }

    @Test
    fun cloudCredentialsLanguageAndEndpointDoNotChangeTheSelectedEngine() {
        for (provider in listOf("elevenlabs", "cartesia", "kawa")) {
            assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_provider", provider))
            assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_${provider}_key", provider))
            assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_${provider}_language", provider))
            assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("cloud_asr_endpoint", provider))
        }
    }

    @Test
    fun inactiveProviderSettingsDoNotAffectCurrentEngineIdentity() {
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_elevenlabs_key", "local"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_cartesia_language", "local"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_local_model", "cartesia"))
    }

    @Test
    fun unknownProviderUsesThePlaybackFallbackAndLocalModelIdentity() {
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_local_model", "unknown"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_kawa_key", "unknown"))
    }
}
