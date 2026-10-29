package io.translatetube.local.player

import io.translatetube.local.translate.EngineFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationConfigPreferencePolicyTest {
    @Test
    fun freeModelPreferencesAreRelevantOnlyWhenThatModelIsSelected() {
        assertTrue(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "free_gemini_model",
                EngineFactory.ID_FREE_GEMINI,
            ),
        )
        assertFalse(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "free_gemini_model",
                EngineFactory.ID_FREE_DEEPSEEK,
            ),
        )
        assertFalse(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "free_deepseek_model",
                EngineFactory.ID_FREE_GEMINI,
            ),
        )
    }

    @Test
    fun localModelSettingsAreIsolatedBySelectedLocalEngine() {
        assertTrue(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "opus_manual_source_language",
                EngineFactory.ID_LOCAL_OPUS,
            ),
        )
        assertFalse(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "opus_manual_source_language",
                EngineFactory.ID_LOCAL_MLKIT,
            ),
        )
        assertTrue(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "mlkit_manual_source_language",
                EngineFactory.ID_LOCAL_MLKIT,
            ),
        )
    }

    @Test
    fun activeProfileFingerprintIsRelevantOnlyForProfileBackedEngines() {
        assertTrue(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                TranslationConfigPreferencePolicy.ACTIVE_PROFILE_FINGERPRINT_KEY,
                123L,
            ),
        )
        val builtInIds = listOf(
            EngineFactory.ID_GOOGLE,
            EngineFactory.ID_GOOGLE_ALT,
            EngineFactory.ID_MICROSOFT,
            EngineFactory.ID_BAIDU,
            EngineFactory.ID_DEEPL,
            EngineFactory.ID_FREE_GEMINI,
            EngineFactory.ID_FREE_DEEPSEEK,
            EngineFactory.ID_LOCAL_MLKIT,
            EngineFactory.ID_LOCAL_OPUS,
            EngineFactory.ID_LOCAL_SMALL100,
            EngineFactory.ID_LOCAL_SINGLE_PAIR,
        )
        builtInIds.forEach { activeId ->
            assertFalse(
                "built-in translator $activeId must ignore the API-profile fingerprint",
                TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                    TranslationConfigPreferencePolicy.ACTIVE_PROFILE_FINGERPRINT_KEY,
                    activeId,
                ),
            )
        }
    }

    @Test
    fun activeEngineAndCommonTranslationPreferencesAlwaysRemainRelevant() {
        assertTrue(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "active_api_id",
                EngineFactory.ID_LOCAL_OPUS,
            ),
        )
        assertTrue(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "translation_source_language",
                EngineFactory.ID_FREE_GEMINI,
            ),
        )
    }

    @Test
    fun unknownTranslationPreferencesRemainRelevantForCompatibility() {
        assertTrue(
            TranslationConfigPreferencePolicy.isRelevantForActiveEngine(
                "custom_translation_option",
                EngineFactory.ID_LOCAL_OPUS,
            ),
        )
    }
}
