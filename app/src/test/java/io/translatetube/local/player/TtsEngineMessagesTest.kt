package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEngineMessagesTest {
    @Test
    fun systemTtsFailureNeverSuggestsDownloadingKokoro() {
        val partial = TtsEngineMessages.partialFailure("系统 TTS")
        val unavailable = TtsEngineMessages.engineUnavailable("系统 TTS")
        assertTrue(partial.contains("系统文字转语音"))
        assertTrue(unavailable.contains("系统文字转语音"))
        assertFalse(partial.contains("Kokoro"))
        assertFalse(unavailable.contains("Kokoro"))
    }

    @Test
    fun kokoroFailureOnlyMentionsKokoroModel() {
        val partial = TtsEngineMessages.partialFailure("Kokoro")
        val unavailable = TtsEngineMessages.engineUnavailable("Kokoro")
        assertTrue(partial.contains("Kokoro"))
        assertTrue(unavailable.contains("Kokoro"))
        assertFalse(partial.contains("系统文字转语音"))
        assertFalse(unavailable.contains("系统文字转语音"))
    }

    @Test
    fun storedIdsAndVisibleNamesResolveToSameEngine() {
        assertTrue(TtsEngineMessages.partialFailure("system").contains("系统文字转语音"))
        assertTrue(TtsEngineMessages.partialFailure("kokoro").contains("Kokoro"))
        assertTrue(TtsEngineMessages.displayName("系统 TTS").contains("系统"))
    }
}
