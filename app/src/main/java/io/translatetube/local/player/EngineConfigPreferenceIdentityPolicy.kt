package io.translatetube.local.player

import io.translatetube.local.tts.SystemTtsSpeakerVoicePolicy

/**
 * Runtime-discovered catalog data is not a user engine preference. System TTS still includes the
 * selected voice and discovered catalog in its PCM cache identity, but writing that catalog must
 * not itself schedule a pipeline restart.
 */
internal object EngineConfigPreferenceIdentityPolicy {
    fun isFingerprintRelevant(key: String): Boolean =
        key != SystemTtsSpeakerVoicePolicy.CATALOG_PREF_KEY

    /**
     * A shared Gemini key belongs to whichever active consumer uses it.
     * It must not disappear just because the other consumer selects another engine.
     */
    fun isRelevantForSelectedEngines(
        key: String,
        selectedTtsEngine: String?,
        activeTranslationId: Long,
    ): Boolean {
        if (!isFingerprintRelevant(key)) return false
        if (key == "free_gemini_key") {
            return TranslationConfigPreferencePolicy.isRelevantForActiveEngine(key, activeTranslationId) ||
                DubbingConfigPreferencePolicy.isRelevantForActiveEngine(key, selectedTtsEngine)
        }
        return DubbingConfigPreferencePolicy.isRelevantForActiveEngine(key, selectedTtsEngine)
    }
}
