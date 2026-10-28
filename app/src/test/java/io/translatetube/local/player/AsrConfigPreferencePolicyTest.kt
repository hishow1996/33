package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrConfigPreferencePolicyTest {
    @Test
    fun localFingerprintIncludesItsModelAndLocalDecoderSettings() {
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_provider", "local"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_local_model", "local"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("local_asr_threads", "local"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("whisper_beam_size", "local"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("sensevoice_threads", "local"))
    }

    @Test
    fun localFingerprintIgnoresInactiveCloudCredentialsAndLanguages() {
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_elevenlabs_key", "local"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_cartesia_language", "local"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_kawa_key", "local"))
    }

    @Test
    fun cloudFingerprintIncludesOnlyItsOwnProviderSettings() {
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_cartesia_key", "cartesia"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_cartesia_language", "cartesia"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_kawa_key", "cartesia"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_local_model", "cartesia"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("whisper_beam_size", "cartesia"))
    }

    @Test
    fun sharedCloudSettingsAreIncludedOnlyWhenCloudTranscriptionIsActive() {
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("cloud_asr_timeout", "local"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("cloud_asr_timeout", "kawa"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("transcription_normalize", "local"))
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("transcription_normalize", "cartesia"))
    }

    @Test
    fun unknownProviderUsesTheSameFallbackAsThePlaybackPipeline() {
        assertTrue(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_local_model", "unknown"))
        assertFalse(AsrConfigPreferencePolicy.isRelevantForActiveProvider("asr_kawa_key", "unknown"))
    }
}
