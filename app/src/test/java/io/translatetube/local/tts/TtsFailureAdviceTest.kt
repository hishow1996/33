package io.translatetube.local.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsFailureAdviceTest {
    @Test
    fun systemTtsAdviceDoesNotAskForKokoroModel() {
        val message = TtsFailureAdvice.missingAudioHint("系统 TTS")
        assertTrue(message.contains("文字转语音"))
        assertTrue(message.contains("中文语音数据"))
        assertFalse(message.contains("重新准备模型"))
        assertFalse(message.contains("请检查 Kokoro"))
    }

    @Test
    fun kokoroAdviceMentionsItsModelFiles() {
        val message = TtsFailureAdvice.missingAudioHint("Kokoro")
        assertTrue(message.contains("Kokoro"))
        assertTrue(message.contains("模型文件"))
    }

    @Test
    fun unknownEngineGetsNeutralAdvice() {
        val message = TtsFailureAdvice.missingAudioHint("")
        assertTrue(message.contains("当前选择的配音引擎"))
        assertFalse(message.contains("下载 Kokoro 模型"))
    }
}
