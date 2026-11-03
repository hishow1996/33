package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerRecognitionProgressPolicyTest {

    @Test
    fun audioAcquisitionUsesMeasuredDownloadPercentage() {
        val update = SpeakerRecognitionProgressPolicy.audioAcquisitionProgress(
            "YouTube · 独立音轨 · 下载 12 MB / 48 MB · 25%",
        )
        assertEquals(SpeakerRecognitionStage.PREPARING_AUDIO, update.stage)
        assertEquals(25, update.progressPercent)
        assertEquals("音轨", update.progressItem)
    }

    @Test
    fun displayMessageSeparatesByteCountsAndRemovesDuplicatePercentage() {
        val display = SpeakerRecognitionProgressPolicy.displayMessage(
            "YouTube · 独立音轨 · 正在下载 40.0 KB / 1.6 MB · 2%",
        )
        assertEquals("YouTube · 独立音轨 · 下载中", display.message)
        assertEquals("40 KB / 1.6 MB", display.sizeDetail)
    }

    @Test
    fun displayMessageKeepsHlsCountersAndDoesNotInventByteDetails() {
        val display = SpeakerRecognitionProgressPolicy.displayMessage(
            "网页音轨 · 分段下载 3/10 · 30%",
        )
        assertEquals("网页音轨 · 分段下载 3/10", display.message)
        assertNull(display.sizeDetail)
    }

    @Test
    fun displayMessagePreservesEstimatedTotalLabel() {
        val display = SpeakerRecognitionProgressPolicy.displayMessage(
            "本地音轨 · 正在下载 1.5 MB / 预计约 8.0 MB · 18%",
        )
        assertEquals("本地音轨 · 下载中", display.message)
        assertEquals("1.5 MB / 预计约 8 MB", display.sizeDetail)
    }

    @Test
    fun stageDisplaySeparatesAudioDownloadStatusAndMeasuredSize() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.PREPARING,
                stage = SpeakerRecognitionStage.PREPARING_AUDIO,
                message = "YouTube · 独立音轨 · 正在下载 40.0 KB / 1.6 MB · 2%",
                progressPercent = 2,
            ),
        )
        assertEquals("YouTube · 独立音轨 · 下载中", display.message)
        assertEquals("40 KB / 1.6 MB", display.secondaryDetail)
    }

    @Test
    fun modelDownloadShowsRealByteCounts() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.DOWNLOADING_MODELS,
                stage = SpeakerRecognitionStage.DOWNLOADING_MODELS,
                message = "正在下载人物分离模型",
                progressPercent = 25,
                completedUnits = 512L * 1024L,
                totalUnits = 4L * 1024L * 1024L,
                progressItem = "正在下载人物分离模型",
            ),
        )
        assertEquals("正在下载人物分离模型", display.message)
        assertEquals("512 KB / 4 MB", display.secondaryDetail)
    }

    @Test
    fun analysisShowsCurrentSegmentWithoutRecalculatingProgress() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.ANALYZING,
                stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
                message = "正在分析当前音频分段…",
                progressPercent = 37,
                completedUnits = 1L,
                totalUnits = 4L,
                progressItem = "模型内部分析",
            ),
        )
        assertEquals("正在分析当前音频分段…", display.message)
        assertEquals("当前分段 2/4", display.secondaryDetail)
    }

    @Test
    fun firstAnalysisChunkShowsCurrentSegmentRatherThanCompletedCount() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.ANALYZING,
                stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
                message = "正在分析音频分段…",
                completedUnits = 0L,
                totalUnits = 1L,
            ),
        )
        assertEquals("当前分段 1/1", display.secondaryDetail)
    }

    @Test
    fun subtitleLinkingShowsCueAndPersonCounts() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.APPLYING,
                stage = SpeakerRecognitionStage.LINKING_SUBTITLES,
                message = "正在关联人物与字幕…",
                progressPercent = 60,
                completedUnits = 60L,
                totalUnits = 100L,
                personCount = 3,
            ),
        )
        assertEquals("已关联 60/100 行 · 3 位人物", display.secondaryDetail)
    }

    @Test
    fun applyingVoicesShowsDetectedPeopleCount() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.APPLYING,
                stage = SpeakerRecognitionStage.APPLYING_VOICES,
                message = "已识别 3 位人物，正在更新人物音色…",
                personCount = 3,
            ),
        )
        assertEquals("3 位人物", display.secondaryDetail)
    }

    @Test
    fun failureDetailIsNotStrippedAsProgressMetadata() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.FAILED,
                stage = SpeakerRecognitionStage.PREPARING_AUDIO,
                message = "人物识别失败",
                error = "下载失败：HTTP 403，服务器拒绝访问；已接收 40 KB。",
            ),
        )
        assertEquals("下载失败：HTTP 403，服务器拒绝访问；已接收 40 KB。", display.message)
        assertNull(display.secondaryDetail)
    }

    @Test
    fun completedDisplayMovesPersonCountToItsOwnDetailLine() {
        val display = SpeakerRecognitionProgressPolicy.display(
            SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.SUCCEEDED,
                stage = SpeakerRecognitionStage.APPLYING_VOICES,
                message = "识别完成 · 3 位人物",
                personCount = 3,
            ),
        )
        assertEquals("识别完成", display.message)
        assertEquals("3 位人物", display.secondaryDetail)
    }

    @Test
    fun audioAcquisitionWithoutPercentageRemainsIndeterminate() {
        val update = SpeakerRecognitionProgressPolicy.audioAcquisitionProgress(
            "网页媒体流 · 正在提取音轨…",
        )
        assertEquals(SpeakerRecognitionStage.PREPARING_AUDIO, update.stage)
        assertNull(update.progressPercent)
    }

    @Test
    fun speakerAudioDownloadCannotReplaceThePlayerProcessingPhase() {
        val current = PlayerUiState(
            key = "youtube:video",
            phase = Phase.READY,
            progress = "播放中",
            transcriptionProgressPercent = 60,
            speakerRecognition = SpeakerRecognitionUiState(
                phase = SpeakerRecognitionPhase.PREPARING,
                stage = SpeakerRecognitionStage.PREPARING_AUDIO,
                message = "正在准备音频…",
            ),
        )
        val requested = current.copy(
            phase = Phase.DOWNLOADING_AUDIO,
            progress = "YouTube · 独立音轨 · 下载 32%",
            transcriptionProgressPercent = 0,
        )
        val result = SpeakerRecognitionProgressPolicy.preservePlaybackState(
            current = current,
            requested = requested,
            speakerRecognitionOnly = true,
        )
        assertEquals(Phase.READY, result.phase)
        assertEquals("播放中", result.progress)
        assertEquals(60, result.transcriptionProgressPercent)
        assertEquals("YouTube · 独立音轨 · 下载 32%", result.speakerRecognition.message)
        assertEquals(32, result.speakerRecognition.progressPercent)
        assertEquals("音轨", result.speakerRecognition.progressItem)
    }

    @Test
    fun normalPipelineDownloadKeepsItsOriginalPhaseAndProgress() {
        val current = PlayerUiState(key = "youtube:video", phase = Phase.PARSING)
        val requested = current.copy(
            phase = Phase.DOWNLOADING_AUDIO,
            progress = "YouTube · 正在下载 20%",
        )
        val result = SpeakerRecognitionProgressPolicy.preservePlaybackState(
            current = current,
            requested = requested,
            speakerRecognitionOnly = false,
        )
        assertEquals(Phase.DOWNLOADING_AUDIO, result.phase)
        assertEquals("YouTube · 正在下载 20%", result.progress)
    }

    @Test
    fun audioAcquisitionParsesDecimalPercentageAndOptionalSpace() {
        val update = SpeakerRecognitionProgressPolicy.audioAcquisitionProgress(
            "网页音轨下载 42.5 %",
        )
        assertEquals(42, update.progressPercent)
    }

    @Test
    fun speakerAudioProgressDoesNotCrossSessionBoundary() {
        val current = PlayerUiState(
            key = "youtube:current",
            phase = Phase.READY,
            progress = "当前视频播放中",
            audioProgress = AudioProgressSnapshot(downloadedBytes = 65L, totalBytes = 100L),
        )
        val requested = current.copy(
            key = "local:next",
            phase = Phase.DOWNLOADING_AUDIO,
            progress = "本地音轨下载 70%",
        )
        val result = SpeakerRecognitionProgressPolicy.preservePlaybackState(
            current = current,
            requested = requested,
            speakerRecognitionOnly = true,
        )
        assertEquals("local:next", result.key)
        assertEquals(Phase.DOWNLOADING_AUDIO, result.phase)
        assertEquals("本地音轨下载 70%", result.progress)
    }

    @Test
    fun singleChunkUsesIndeterminateProgressWhileRunning() {
        val update = SpeakerRecognitionProgressPolicy.analysisChunkStarted(1L, 1L)
        assertEquals(SpeakerRecognitionStage.ANALYZING_AUDIO, update.stage)
        assertNull(update.progressPercent)
        assertEquals(0L, update.completedUnits)
        assertEquals(1L, update.totalUnits)
    }

    @Test
    fun multiChunkProgressUsesOnlyCompletedChunks() {
        val update = SpeakerRecognitionProgressPolicy.analysisChunkStarted(2L, 5L)
        assertEquals(20, update.progressPercent)
        assertEquals(1L, update.completedUnits)
        assertEquals(5L, update.totalUnits)
    }

    @Test
    fun completedChunkAdvancesMeasuredProgress() {
        val update = SpeakerRecognitionProgressPolicy.analysisChunkCompleted(2L, 5L)
        assertEquals(40, update.progressPercent)
        assertEquals("已完成音频分析", update.message)
    }

    @Test
    fun completedSingleChunkReachesOneHundredPercent() {
        assertEquals(100, SpeakerRecognitionProgressPolicy.analysisChunkCompleted(1L, 1L).progressPercent)
    }

    @Test
    fun nativeInferenceProgressUsesCompletedAudioChunksAndCurrentWorkFraction() {
        val update = SpeakerRecognitionProgressPolicy.analysisInferenceProgress(
            completedChunks = 1L,
            totalChunks = 4L,
            processedInferenceUnits = 2L,
            totalInferenceUnits = 4L,
        )
        assertEquals(37, update.progressPercent)
        assertEquals(1L, update.completedUnits)
        assertEquals(4L, update.totalUnits)
        assertEquals("模型内部分析", update.progressItem)
    }

    @Test
    fun nativeInferenceProgressClampsInvalidCounts() {
        val update = SpeakerRecognitionProgressPolicy.analysisInferenceProgress(
            completedChunks = 8L,
            totalChunks = 4L,
            processedInferenceUnits = 10L,
            totalInferenceUnits = 2L,
        )
        assertEquals(100, update.progressPercent)
        assertEquals(4L, update.completedUnits)
        assertEquals(4L, update.totalUnits)
    }

    @Test
    fun percentForCountIsBoundedAndRejectsUnknownTotal() {
        assertEquals(0, SpeakerRecognitionProgressPolicy.percentForCount(0L, 20L))
        assertEquals(55, SpeakerRecognitionProgressPolicy.percentForCount(11L, 20L))
        assertEquals(100, SpeakerRecognitionProgressPolicy.percentForCount(24L, 20L))
        assertNull(SpeakerRecognitionProgressPolicy.percentForCount(0L, 0L))
    }
}