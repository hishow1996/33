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
    fun actualSystemTtsSelectionPreferencesRemainFingerprintRelevant() {
        assertTrue(
            EngineConfigPreferenceIdentityPolicy.isFingerprintRelevant("system_tts_voice_name"),
        )
        assertTrue(
            EngineConfigPreferenceIdentityPolicy.isFingerprintRelevant("tts_engine"),
        )
    }
}
