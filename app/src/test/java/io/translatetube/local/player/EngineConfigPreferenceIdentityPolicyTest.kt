package io.translatetube.local.player

import io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConfigPreferenceIdentityPolicyTest {
    @Test
    fun runtimeVoiceCatalogIsExcludedFromEngineConfigurationFingerprint() {
        assertFalse(
            EngineConfigPreferenceIdentityPolicy.isFingerprintRelevant(
                SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY,
            ),
        )
    }

    @Test
    fun sharedGeminiKeyIsRelevantToEitherActiveConsumer() {
        assertTrue(
            EngineConfigPreferenceIdentityPolicy.isRelevantForSelectedEngines(
                "free_gemini_key",
                selectedTtsEngine = "edge",
                activeTranslationId = io.translatetube.local.translate.EngineFactory.ID_FREE_GEMINI.toLong(),
            ),
        )
        assertTrue(
            EngineConfigPreferenceIdentityPolicy.isRelevantForSelectedEngines(
                "free_gemini_key",
                selectedTtsEngine = "gemini",
                activeTranslationId = io.translatetube.local.translate.EngineFactory.ID_FREE_DEEPSEEK.toLong(),
            ),
        )
        assertFalse(
            EngineConfigPreferenceIdentityPolicy.isRelevantForSelectedEngines(
                "free_gemini_key",
                selectedTtsEngine = "edge",
                activeTranslationId = io.translatetube.local.translate.EngineFactory.ID_FREE_DEEPSEEK.toLong(),
            ),
        )
    }

    @Test
    fun actualSystemTtsSelectionPreferencesRemainFingerprintRelevant() {
        assertTrue(
            EngineConfigPreferenceIdentityPolicy.isFingerprintRelevant("system_tts_voice_name"),
        )
        assertTrue(
            EngineConfigPreferenceIdentityPolicy.isFingerprintRelevant("tts_engine"),
        )
    }
}
