package io.translatetube.local.player

import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.translate.FreeModelDefaults
import io.translatetube.local.translate.SourceLanguagePolicy

/**
 * Builds cache identities for online built-in translators whose configuration is stored in
 * SharedPreferences instead of Room API profiles.
 *
 * These fingerprints are deliberately based on effective values, including defaults, and only
 * include settings belonging to the selected engine. The generic identity helper hashes secrets
 * before producing a value used in cache keys.
 */
internal object BuiltInTranslationCacheIdentityPolicy {
    private data class Config(
        val providerKind: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    )

    fun fingerprint(
        activeId: Long,
        sourceLanguageMode: String?,
        geminiModel: String?,
        geminiApiKey: String?,
        deepSeekModel: String?,
        deepSeekBaseUrl: String?,
        deepSeekApiKey: String?,
    ): String? {
        val config = when (activeId) {
            EngineFactory.ID_FREE_GEMINI -> Config(
                providerKind = "free-gemini",
                baseUrl = FreeModelDefaults.GEMINI_BASE_URL,
                model = geminiModel.orEmpty().trim().ifBlank { FreeModelDefaults.GEMINI_MODEL },
                apiKey = geminiApiKey.orEmpty().trim(),
            )
            EngineFactory.ID_FREE_DEEPSEEK -> Config(
                providerKind = "free-deepseek",
                baseUrl = deepSeekBaseUrl.orEmpty().trim().ifBlank {
                    FreeModelDefaults.DEEPSEEK_BASE_URL
                },
                model = deepSeekModel.orEmpty().trim().ifBlank { FreeModelDefaults.DEEPSEEK_MODEL },
                apiKey = deepSeekApiKey.orEmpty().trim(),
            )
            else -> return null
        }

        val sourceMode = sourceLanguageMode.orEmpty().ifBlank { "auto" }
        val scopedProviderKind = if (SourceLanguagePolicy.isUndetermined(sourceMode)) {
            config.providerKind
        } else {
            "${config.providerKind}|source=$sourceMode"
        }
        return TranslationCacheIdentity.providerFingerprint(
            activeId = activeId,
            providerKind = scopedProviderKind,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
        )
    }
}
