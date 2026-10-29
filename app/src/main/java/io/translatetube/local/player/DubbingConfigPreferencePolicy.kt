package io.translatetube.local.player

/**
 * Only the selected TTS engine's private settings should invalidate a dubbing result.
 * Shared settings (including the engine selector itself) remain relevant to every session.
 */
internal object DubbingConfigPreferencePolicy {
    fun isRelevantForActiveEngine(
        key: String,
        selectedTtsEngine: String?,
    ): Boolean {
        val engine = selectedTtsEngine?.trim()?.lowercase().orEmpty().ifBlank { "edge" }
        return when {
            key == "tts_engine" -> true
            key.startsWith("system_tts_") -> engine == "system"
            key.startsWith("piper_") -> engine == "piper"
            key.startsWith("kokoro_") -> engine == "kokoro"
            key.startsWith("edge_tts_") -> engine == "edge"
            key.startsWith("web_tts_") -> engine == "web"
            key.startsWith("google_tts_") -> engine == "google"
            key.startsWith("gemini_tts_") -> engine == "gemini"
            key == "free_gemini_key" -> engine == "gemini"
            key.startsWith("online_tts_") -> engine == "edge" || engine == "web" || engine == "google" || engine == "gemini"
            key.startsWith("cloud_tts_") -> engine == "edge" || engine == "web" || engine == "google" || engine == "gemini"
            key.startsWith("local_tts_") -> engine == "system" || engine == "piper" || engine == "kokoro"
            // Generic TTS options and unclassified legacy preferences may affect more than one engine.
            else -> true
        }
    }
}
