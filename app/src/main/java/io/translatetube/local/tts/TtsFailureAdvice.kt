package io.translatetube.local.tts

import java.util.Locale

/**
 * Engine-specific recovery guidance for dubbing failures.
 * System TTS is a device service and must never be presented as requiring the Kokoro model.
 */
internal object TtsFailureAdvice {
    fun missingAudioHint(engineName: String): String {
        val normalized = engineName
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), "")
        val advice = when {
            normalized.contains("系统tts") || normalized == "system" ->
                "请检查手机的文字转语音引擎、中文语音数据和该音色是否需要网络；系统 TTS 直接使用手机语音服务，不依赖 Kokoro 配音模型。"
            normalized.contains("kokoro") ->
                "请检查 Kokoro 配音模型文件是否完整，必要时在设置中重新准备模型。"
            normalized.contains("piper") ->
                "请确认设置中选择了中文 Piper 模型，并检查模型、词典和发音规则文件是否完整。"
            else ->
                "请检查当前选择的配音引擎是否初始化成功，以及它是否能生成有效音频。"
        }
        return "部分字幕没有可播放的中文配音（可能是译文为空或合成失败），已自动恢复原声，避免出现无声片段。$advice"
    }
}
