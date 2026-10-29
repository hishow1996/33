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
}
