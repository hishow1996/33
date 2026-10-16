package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class PiperDownloadStagePolicyTest {
    @Test
    fun networkAndPreparationStagesRemainDownloadProgress() {
        assertEquals(
            PiperModelDownloadStatus.DOWNLOADING,
            PiperDownloadStagePolicy.statusFor("正在连接 Piper 官方模型源…"),
        )
        assertEquals(
            PiperModelDownloadStatus.DOWNLOADING,
            PiperDownloadStagePolicy.statusFor("正在检查模型下载和安装所需空间…"),
        )
    }

    @Test
    fun extractingAndInstallValidationAreShownAsIndeterminateInstallStages() {
        assertEquals(
            PiperModelDownloadStatus.EXTRACTING,
            PiperDownloadStagePolicy.statusFor("正在解压并安装模型（已处理 64 MB）…"),
        )
        assertEquals(
            PiperModelDownloadStatus.EXTRACTING,
            PiperDownloadStagePolicy.statusFor("模型文件校验通过，正在安装…"),
        )
    }

    @Test
    fun readyIsPublishedOnlyAfterTheModelHasBeenInstalled() {
        assertEquals(
            PiperModelDownloadStatus.READY,
            PiperDownloadStagePolicy.statusFor("Piper 模型已就绪"),
        )
    }
}
