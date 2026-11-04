package io.translatetube.local.player

import java.util.Locale

/**
 * ASR history identity follows the selected recognizer, not its mutable runtime options.
 *
 * Credentials, endpoints, language hints, thread counts and decoder tuning can be edited without
 * changing the transcription engine. They must not invalidate already-produced source subtitles.
 * The selected provider and, for the local provider, the selected model are the engine identity.
 */
internal object AsrConfigPreferencePolicy {
    private val supportedProviders = setOf("local", "elevenlabs", "cartesia", "kawa")

    fun normalizeProvider(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it in supportedProviders }
            ?: "local"

    fun isRelevantForActiveProvider(key: String, selectedProvider: String?): Boolean {
        val normalizedKey = key.trim().lowercase(Locale.ROOT)
        val provider = normalizeProvider(selectedProvider)
        return normalizedKey == "asr_provider" ||
            (provider == "local" && normalizedKey == "asr_local_model")
    }
}
