package io.translatetube.local.player

import io.translatetube.local.translate.EngineFactory

/**
 * Ignore engine-specific preferences that cannot affect the currently selected translator.
 *
 * The active API profile is represented by its one-way profile fingerprint in SharedPreferences;
 * local and built-in engines use their own preference namespaces. Common translation preferences
 * remain part of every engine's identity.
 */
internal object TranslationConfigPreferencePolicy {
    const val ACTIVE_PROFILE_FINGERPRINT_KEY = "translation_active_api_profile_fingerprint"

    fun isRelevantForActiveEngine(
        key: String,
        activeTranslationId: Long,
    ): Boolean {
        if (
            key == "active_api_id" ||
            key == ACTIVE_PROFILE_FINGERPRINT_KEY ||
            key == "translation_source_language"
        ) return true

        return when {
            key.startsWith("free_gemini_") ->
                activeTranslationId == EngineFactory.ID_FREE_GEMINI

            key.startsWith("free_deepseek_") ->
                activeTranslationId == EngineFactory.ID_FREE_DEEPSEEK

            key.startsWith("mlkit_") ->
                activeTranslationId == EngineFactory.ID_LOCAL_MLKIT

            key.startsWith("opus_") ->
                activeTranslationId == EngineFactory.ID_LOCAL_OPUS

            key.startsWith("small100_") ->
                activeTranslationId == EngineFactory.ID_LOCAL_SMALL100

            key.startsWith("single_pair_") ->
                activeTranslationId == EngineFactory.ID_LOCAL_SINGLE_PAIR

            // Preserve unclassified/common translation settings until their consumer is known.
            else -> true
        }
    }
}
