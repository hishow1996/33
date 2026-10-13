package io.translatetube.local.player

/** Keep recovery advice tied to the engine the user actually selected. */
internal object TtsEngineMessages {
    fun displayName(value: String?): String = when {
        value.equals("system", ignoreCase = true) -> "系统 TTS"
        value.equals("kokoro", ignoreCase = true) -> "Kokoro"
        value.orEmpty().contains("系统", ignoreCase = true) -> "系统 TTS"
        value.orEmpty().contains("Kokoro", ignoreCase = true) -> "Kokoro"
        else -> "未知配音引擎"
    }

    fun partialFailure(engineName: String): String = when (displayName(engineName)) {
        "系统 TTS" ->
            "部分字幕未能合成配音，已自动恢复原声。请检查手机的系统文字转语音服务是否已启用，并确认已安装中文语音数据。"
        "Kokoro" ->
            "部分字幕未能合成配音，已自动恢复原声。请检查 Kokoro 配音模型文件是否完整，然后重试。"
        else ->
            "部分字幕未能合成配音，已自动恢复原声。请在设置中重新选择配音引擎，并检查对应的语音组件。"
    }

    fun supportsKokoroSpeakers(engineName: String): Boolean = engineName.trim() == "Kokoro"

    fun engineUnavailable(engineName: String): String = when (displayName(engineName)) {
        "系统 TTS" ->
            "配音引擎未能启动，已恢复视频原声。请检查手机的系统文字转语音服务和中文语音数据。"
        "Kokoro" ->
            "配音引擎未能启动，已恢复视频原声。请检查 Kokoro 配音模型文件是否完整。"
        else ->
            "配音引擎未能启动，已恢复视频原声。请在设置中重新选择配音引擎并检查对应的语音组件。"
    }
}
