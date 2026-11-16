package io.translatetube.local.player

import io.translatetube.local.asr.WhisperOutputModePolicy
import java.util.Locale

/**
 * ASR history identity follows the selected recognizer and its output mode when Whisper is active.
 * Credentials and unrelated provider settings do not invalidate an existing source transcript.
 */
internal object AsrConfigPreferencePolicy {
    private val supportedProviders = setOf("local", "elevenlabs", "cartesia", "kawa")

    fun normalizeProvider(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it in supportedProviders }
            ?: "local"

    fun isRelevantForActiveProvider(
        key: String,
        selectedProvider: String?,
        selectedLocalModelId: String = "whisper",
    ): Boolean {
        val normalizedKey = key.trim().lowercase(Locale.ROOT)
        val provider = normalizeProvider(selectedProvider)
        val localModel = selectedLocalModelId.trim().lowercase(Locale.ROOT)
        val isWhisperSelected = localModel == "whisper" ||
            localModel.startsWith("whisper-") ||
            localModel.startsWith("whisper_")
        return normalizedKey == "asr_provider" ||
            (provider == "local" && normalizedKey == "asr_local_model") ||
            (provider == "local" && isWhisperSelected &&
                normalizedKey == WhisperOutputModePolicy.PREF_KEY)
    }
}
