package io.translatetube.local.player

import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.translate.FreeModelDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInTranslationCacheIdentityPolicyTest {
    private fun fingerprint(
        activeId: Long = EngineFactory.ID_FREE_GEMINI,
        sourceLanguageMode: String? = "auto",
        geminiModel: String? = null,
        geminiApiKey: String? = null,
        deepSeekModel: String? = null,
        deepSeekBaseUrl: String? = null,
        deepSeekApiKey: String? = null,
    ): String? = BuiltInTranslationCacheIdentityPolicy.fingerprint(
        activeId = activeId,
        sourceLanguageMode = sourceLanguageMode,
        geminiModel = geminiModel,
        geminiApiKey = geminiApiKey,
        deepSeekModel = deepSeekModel,
        deepSeekBaseUrl = deepSeekBaseUrl,
        deepSeekApiKey = deepSeekApiKey,
    )

    @Test
    fun freeGeminiModelAndCredentialArePartOfTranslationCacheIdentity() {
        val original = fingerprint(geminiModel = "gemini-model-a", geminiApiKey = "key-a")
        assertEquals(original, fingerprint(geminiModel = "gemini-model-a", geminiApiKey = "key-a"))
        assertNotEquals(original, fingerprint(geminiModel = "gemini-model-b", geminiApiKey = "key-a"))
        assertNotEquals(original, fingerprint(geminiModel = "gemini-model-a", geminiApiKey = "key-b"))
    }

    @Test
    fun freeDeepSeekModelEndpointAndCredentialArePartOfTranslationCacheIdentity() {
        val original = fingerprint(
            activeId = EngineFactory.ID_FREE_DEEPSEEK,
            deepSeekModel = "deepseek-model-a",
            deepSeekBaseUrl = "https://api-a.example/v1",
            deepSeekApiKey = "key-a",
        )
        assertEquals(
            original,
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                deepSeekModel = "deepseek-model-a",
                deepSeekBaseUrl = "https://api-a.example/v1/",
                deepSeekApiKey = "key-a",
            ),
        )
        assertNotEquals(
            original,
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                deepSeekModel = "deepseek-model-b",
                deepSeekBaseUrl = "https://api-a.example/v1",
                deepSeekApiKey = "key-a",
            ),
        )
        assertNotEquals(
            original,
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                deepSeekModel = "deepseek-model-a",
                deepSeekBaseUrl = "https://api-b.example/v1",
                deepSeekApiKey = "key-a",
            ),
        )
        assertNotEquals(
            original,
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                deepSeekModel = "deepseek-model-a",
                deepSeekBaseUrl = "https://api-a.example/v1",
                deepSeekApiKey = "key-b",
            ),
        )
    }

    @Test
    fun missingOrBlankSettingsResolveToTheSameEffectiveDefaults() {
        assertEquals(
            fingerprint(),
            fingerprint(
                geminiModel = "",
                geminiApiKey = "",
            ),
        )
        assertEquals(
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
            ),
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                deepSeekModel = "",
                deepSeekBaseUrl = "",
                deepSeekApiKey = "",
            ),
        )
        assertEquals(
            fingerprint(),
            fingerprint(geminiModel = FreeModelDefaults.GEMINI_MODEL),
        )
        assertEquals(
            fingerprint(activeId = EngineFactory.ID_FREE_DEEPSEEK),
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                deepSeekModel = FreeModelDefaults.DEEPSEEK_MODEL,
                deepSeekBaseUrl = FreeModelDefaults.DEEPSEEK_BASE_URL,
            ),
        )
    }

    @Test
    fun inactiveFreeProviderSettingsDoNotChangeTheSelectedProviderIdentity() {
        assertEquals(
            fingerprint(geminiModel = "gemini-a"),
            fingerprint(geminiModel = "gemini-a", deepSeekModel = "deepseek-b", deepSeekApiKey = "other"),
        )
        assertEquals(
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                deepSeekModel = "deepseek-a",
            ),
            fingerprint(
                activeId = EngineFactory.ID_FREE_DEEPSEEK,
                geminiModel = "gemini-b",
                geminiApiKey = "other",
                deepSeekModel = "deepseek-a",
            ),
        )
    }

    @Test
    fun sourceLanguageModeChangesIdentityOnlyWhenItIsARealLanguage() {
        assertEquals(fingerprint(sourceLanguageMode = "auto"), fingerprint(sourceLanguageMode = "und"))
        assertNotEquals(fingerprint(sourceLanguageMode = "auto"), fingerprint(sourceLanguageMode = "ko"))
    }

    @Test
    fun unsupportedEngineReturnsNoOverride() {
        assertNull(fingerprint(activeId = EngineFactory.ID_GOOGLE))
    }
}
