package io.translatetube.local.player

/**
 * Only the selected TTS engine's private settings should invalidate a dubbing result.
 * Shared settings (including the engine selector itself) remain relevant to every session.
 */
internal object DubbingConfigPreferencePolicy {
    /**
     * Normalize stored engine IDs before the playback pipeline uses them.
     * "web" was a removed legacy provider; existing installations should fall back to Edge TTS.
     */
    fun normalizeEngineId(selectedTtsEngine: String?): String {
        val engine = selectedTtsEngine?.trim()?.lowercase().orEmpty().ifBlank { "edge" }
        return if (engine == "web") "edge" else engine
    }

    fun isDubbingPreferenceKey(key: String): Boolean =
        key.startsWith("tts_") ||
            key.startsWith("edge_tts_") ||
            key.startsWith("google_tts_") ||
            key.startsWith("gemini_tts_") ||
            key.startsWith("online_tts_") ||
            key.startsWith("cloud_tts_") ||
            key.startsWith("local_tts_") ||
            key.startsWith("piper_") ||
            key.startsWith("kokoro_") ||
            key.startsWith("system_tts_")

    fun isRelevantForActiveEngine(
        key: String,
        selectedTtsEngine: String?,
    ): Boolean {
        val engine = normalizeEngineId(selectedTtsEngine)
        return when {
            key == "tts_engine" -> true
            // Leftover Web TTS preferences are inert after the provider was removed.
            key.startsWith("web_tts_") -> false
            key.startsWith("system_tts_") -> engine == "system"
            key.startsWith("piper_") -> engine == "piper"
            key.startsWith("kokoro_") -> engine == "kokoro"
            key.startsWith("edge_tts_") -> engine == "edge"
            key.startsWith("google_tts_") -> engine == "google"
            key.startsWith("gemini_tts_") -> engine == "gemini"
            key == "free_gemini_key" -> false // Translation-only key; never invalidate dubbing.
            // Legacy online/cloud preferences now belong only to Edge TTS.
            key.startsWith("online_tts_") -> engine == "edge"
            key.startsWith("cloud_tts_") -> engine == "edge"
            key.startsWith("local_tts_") -> engine == "system" || engine == "piper" || engine == "kokoro"
            // Generic TTS options and unclassified legacy preferences may affect more than one engine.
            else -> true
        }
    }
}
