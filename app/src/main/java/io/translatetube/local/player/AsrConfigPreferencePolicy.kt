package io.translatetube.local.player

import java.util.Locale

/**
 * Keeps ASR fingerprints scoped to the selected engine so editing an inactive provider does not
 * invalidate the active engine's partial transcript, source subtitles or successful snapshot.
 */
internal object AsrConfigPreferencePolicy {
    private val supportedProviders = setOf("local", "elevenlabs", "cartesia", "kawa")

    fun normalizeProvider(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it in supportedProviders }
            ?: "local"

    fun isRelevantForActiveProvider(key: String, selectedProvider: String?): Boolean {
        val normalizedKey = key.lowercase(Locale.ROOT)
        val provider = normalizeProvider(selectedProvider)

        if (normalizedKey == "asr_provider") return true

        if (normalizedKey.startsWith("asr_")) {
            return if (provider == "local") {
                normalizedKey == "asr_local_model" ||
                    normalizedKey.startsWith("asr_local_") ||
                    normalizedKey.startsWith("asr_whisper_") ||
                    normalizedKey.startsWith("asr_sensevoice_")
            } else {
                normalizedKey.startsWith("asr_${provider}_")
            }
        }

        return when {
            normalizedKey.startsWith("local_asr_") ||
                normalizedKey.startsWith("whisper_") ||
                normalizedKey.startsWith("sensevoice_") -> provider == "local"
            normalizedKey.startsWith("cloud_asr_") ||
                normalizedKey.startsWith("online_asr_") -> provider != "local"
            normalizedKey.contains("transcription") ||
                normalizedKey.contains("transcribe") ||
                normalizedKey.contains("speech_recognition") -> true
            else -> false
        }
    }
}
