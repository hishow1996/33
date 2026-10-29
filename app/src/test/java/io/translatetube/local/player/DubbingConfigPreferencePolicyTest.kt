package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingConfigPreferencePolicyTest {
    @Test
    fun engineSelectorIsAlwaysRelevant() {
        listOf("edge", "web", "system", "kokoro", "piper", "google", "gemini").forEach { engine ->
            assertTrue(
                DubbingConfigPreferencePolicy.isRelevantForActiveEngine("tts_engine", engine),
            )
        }
    }

    @Test
    fun piperSettingsMatterOnlyWhenPiperIsSelected() {
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("piper_model", "piper"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("piper_model", "edge"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("piper_model", "system"))
    }

    @Test
    fun kokoroSettingsMatterOnlyWhenKokoroIsSelected() {
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("kokoro_speaker", "kokoro"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("kokoro_speaker", "edge"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("kokoro_speaker", "piper"))
    }

    @Test
    fun systemVoiceSettingsMatterOnlyWhenSystemTtsIsSelected() {
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("system_tts_mode", "system"))
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("system_tts_voice_name", "system"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("system_tts_mode", "edge"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("system_tts_voice_name", "kokoro"))
    }

    @Test
    fun edgeAndWebProviderSettingsAreSeparated() {
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("edge_tts_voice", "edge"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("edge_tts_voice", "web"))
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("web_tts_voice", "web"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("web_tts_voice", "edge"))
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("online_tts_voice", "web"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("online_tts_voice", "piper"))
    }

    @Test
    fun googleAndGeminiSettingsAreScopedToTheirSelectedEngine() {
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("google_tts_voice", "google"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("google_tts_voice", "gemini"))
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("gemini_tts_voice", "gemini"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("gemini_tts_voice", "google"))
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("free_gemini_key", "gemini"))
        assertFalse(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("free_gemini_key", "google"))
    }

    @Test
    fun genericAndUnknownPreferencesStayRelevant() {
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("tts_engine", "edge"))
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("tts_speed", "edge"))
        assertTrue(DubbingConfigPreferencePolicy.isRelevantForActiveEngine("new_tts_option", "piper"))
    }
}
