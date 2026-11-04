package io.translatetube.local.tts

/** Maps install-stage callbacks to visible model-download phases. */
internal object PiperDownloadStagePolicy {
    fun statusFor(stage: String): PiperModelDownloadStatus = when {
        stage.startsWith("Piper 模型已就绪") -> PiperModelDownloadStatus.READY
        stage.startsWith("正在解压") ||
            stage.startsWith("模型包下载完成并通过校验") ||
            stage.startsWith("模型文件校验") ->
            PiperModelDownloadStatus.EXTRACTING
        else -> PiperModelDownloadStatus.DOWNLOADING
    }
}
