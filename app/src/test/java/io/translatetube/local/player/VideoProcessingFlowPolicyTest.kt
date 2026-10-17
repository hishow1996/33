package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProcessingFlowPolicyTest {
    @Test
    fun detectsSourceFromPlaybackSessionKey() {
        assertEquals(VideoSourceKind.YOUTUBE, VideoProcessingFlowPolicy.sourceKind("youtube:abc123"))
        assertEquals(VideoSourceKind.WEB, VideoProcessingFlowPolicy.sourceKind("remote:https://example.com/video.m3u8"))
        assertEquals(VideoSourceKind.LOCAL, VideoProcessingFlowPolicy.sourceKind("local:content://media/video/1"))
        assertEquals(VideoSourceKind.UNKNOWN, VideoProcessingFlowPolicy.sourceKind(""))
    }

    @Test
    fun eachSourceGetsItsOwnStepLabels() {
        val youtube = VideoProcessingFlowPolicy.steps(VideoSourceKind.YOUTUBE)
        val web = VideoProcessingFlowPolicy.steps(VideoSourceKind.WEB)
        val local = VideoProcessingFlowPolicy.steps(VideoSourceKind.LOCAL)

        assertEquals(5, youtube.size)
        assertEquals(5, web.size)
        assertEquals(5, local.size)
        assertEquals("查找可用字幕轨", youtube[1])
        assertEquals("读取原文字幕与时间轴", youtube[2])
        assertEquals("提取视频音轨", web[1])
        assertTrue(web[2].contains("转写原文"))
        assertEquals("提取本地视频音轨", local[1])
        assertTrue(local[0].contains("本地视频"))
        assertFalse(web.any { it.contains("下载或读取字幕") })
        assertFalse(local.any { it.contains("下载或读取字幕") })
    }

    @Test
    fun youtubeFallsBackToAnAudioAndTranscriptionSpecificFlow() {
        val flow = ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION
        val steps = VideoProcessingFlowPolicy.steps(VideoSourceKind.YOUTUBE, flow)
        assertEquals(6, steps.size)
        assertEquals("查找并读取可用原文字幕", steps[1])
        assertEquals("提取视频音轨", steps[2])
        assertEquals("转写原文并生成时间轴字幕", steps[3])
        assertEquals(2, VideoProcessingFlowPolicy.stageIndex(VideoSourceKind.YOUTUBE, flow, Phase.DOWNLOADING_AUDIO))
        assertEquals(3, VideoProcessingFlowPolicy.stageIndex(VideoSourceKind.YOUTUBE, flow, Phase.TRANSCRIBING))
        assertEquals(3, VideoProcessingFlowPolicy.transcriptionStageIndex(VideoSourceKind.YOUTUBE, flow))
    }

    @Test
    fun importedSubtitlePathsDoNotShowUnrelatedVideoSteps() {
        assertEquals(
            listOf("读取导入字幕并检查时间轴", "将原文字幕翻译成中文", "按时间轴生成中文配音"),
            VideoProcessingFlowPolicy.steps(VideoSourceKind.YOUTUBE, ProcessingFlow.IMPORTED_SUBTITLES),
        )
        assertEquals(
            listOf("读取已翻译字幕并检查时间轴", "按时间轴生成中文配音"),
            VideoProcessingFlowPolicy.steps(VideoSourceKind.LOCAL, ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES),
        )
        assertEquals(
            "已翻译字幕配音",
            VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.LOCAL, ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES),
        )
        assertEquals(
            1,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.LOCAL, ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES, Phase.SYNTHESIZING),
        )
    }

    @Test
    fun parsingAndTranscriptionTitlesReflectSource() {
        assertEquals("正在解析 YouTube 视频",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.YOUTUBE, Phase.PARSING))
        assertEquals("正在识别网页视频",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.WEB, Phase.PARSING))
        assertEquals("正在读取本地视频",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.LOCAL, Phase.PARSING))
        assertEquals("正在提取网页视频音轨",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.WEB, Phase.DOWNLOADING_AUDIO))
        assertEquals("正在转写本地视频原文并生成时间轴字幕",
            VideoProcessingFlowPolicy.mainTitle(VideoSourceKind.LOCAL, Phase.TRANSCRIBING))
        assertEquals("正在读取已翻译字幕",
            VideoProcessingFlowPolicy.mainTitle(
                VideoSourceKind.LOCAL, Phase.PARSING, ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES),
        )
    }

    @Test
    fun everySpeechRecognitionFlowExplicitlyTranslatesOriginalCaptionsBeforeDubbing() {
        val youtube = VideoProcessingFlowPolicy.steps(
            VideoSourceKind.YOUTUBE,
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
        )
        val web = VideoProcessingFlowPolicy.steps(VideoSourceKind.WEB, ProcessingFlow.WEB_SPEECH_RECOGNITION)
        val local = VideoProcessingFlowPolicy.steps(VideoSourceKind.LOCAL, ProcessingFlow.LOCAL_SPEECH_RECOGNITION)

        for (steps in listOf(youtube, web, local)) {
            val transcription = steps.indexOfFirst {
                it.contains("转写原文") || it.contains("本地模型转写原文")
            }
            assertTrue("transcription stage should be present: $steps", transcription >= 0)
            assertEquals("将原文字幕翻译成中文", steps[transcription + 1])
            assertTrue(
                "final stage should describe timeline-aligned dubbing: $steps",
                steps[transcription + 2].contains("按时间轴生成中文配音"),
            )
        }
        assertEquals(
            "正在将原文字幕翻译成中文",
            VideoProcessingFlowPolicy.mainTitle(
                VideoSourceKind.LOCAL,
                Phase.TRANSLATING,
                ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
            ),
        )
    }

    @Test
    fun youtubeSubtitleLookupProgressMovesTheActiveStageWithinParsing() {
        assertEquals(
            0,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.YOUTUBE,
                ProcessingFlow.YOUTUBE_SUBTITLES,
                Phase.PARSING,
                progress = "正在解析 YouTube 视频",
            ),
        )
        assertEquals(
            1,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.YOUTUBE,
                ProcessingFlow.YOUTUBE_SUBTITLES,
                Phase.PARSING,
                progress = "正在查找可用字幕轨…",
            ),
        )
        assertEquals(
            2,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.YOUTUBE,
                ProcessingFlow.YOUTUBE_SUBTITLES,
                Phase.PARSING,
                progress = "正在下载并读取字幕文件…",
            ),
        )
        assertEquals(
            1,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.YOUTUBE,
                ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                Phase.PARSING,
                progress = "正在下载并读取字幕文件…",
            ),
        )
    }

    @Test
    fun failedYouTubeSubtitleLookupRetainsTheSpecificSubstage() {
        assertEquals(
            2,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.YOUTUBE,
                ProcessingFlow.YOUTUBE_SUBTITLES,
                Phase.ERROR,
                failedPhase = Phase.PARSING,
                progress = "正在下载并读取字幕文件…",
            ),
        )
        assertEquals(
            1,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.YOUTUBE,
                ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                Phase.ERROR,
                failedPhase = Phase.PARSING,
                progress = "正在下载并读取字幕文件…",
            ),
        )
    }

    @Test
    fun missingTranscriptionEngineMustNotMarkReadyFlowAsComplete() {
        assertEquals(
            3,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.YOUTUBE,
                ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION,
                Phase.READY,
                waitingForTranscriptionEngine = true,
            ),
        )
        assertEquals(
            2,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.LOCAL,
                ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                Phase.READY,
                waitingForTranscriptionEngine = true,
            ),
        )
        assertFalse(VideoProcessingFlowPolicy.isCompleted(Phase.READY, waitingForTranscriptionEngine = true))
        assertFalse(VideoProcessingFlowPolicy.isCompleted(Phase.READY, waitingForDubbingEngine = true))
        assertTrue(VideoProcessingFlowPolicy.isCompleted(Phase.READY))
        assertFalse(VideoProcessingFlowPolicy.isCompleted(Phase.ERROR))
    }

    @Test
    fun aReadySessionWithAnyDubbingFailureIsNotReportedAsFullyCompleted() {
        assertFalse(VideoProcessingFlowPolicy.isCompleted(Phase.READY, dubbingHasErrors = true))
        assertFalse(
            VideoProcessingFlowPolicy.isCompleted(
                Phase.READY,
                waitingForDubbingEngine = true,
                dubbingHasErrors = true,
            ),
        )
        assertTrue(VideoProcessingFlowPolicy.isCompleted(Phase.READY, dubbingHasErrors = false))
        assertEquals(
            "配音部分失败 · 失败字幕已恢复原声",
            VideoProcessingFlowPolicy.readyNotificationFailureTitle(dubbingHasErrors = true),
        )
        assertEquals(null, VideoProcessingFlowPolicy.readyNotificationFailureTitle(dubbingHasErrors = false))
    }

    @Test
    fun processingPageDependsOnTtsEngineAndActualPlaybackReadiness() {
        assertTrue(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.TRANSLATING,
                playerIsPlaying = true,
            ),
        )
        assertTrue(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.SYNTHESIZING,
                playerIsPlaying = true,
                progressiveTtsPlayback = false,
            ),
        )
        assertTrue(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.READY,
                playerIsPlaying = true,
                dubbingPreparationIncomplete = true,
            ),
        )
        assertFalse(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.READY,
                playerIsPlaying = true,
                dubbingPreparationIncomplete = false,
            ),
        )

        // While the first cue is still being prepared, keep showing progress. Once READY is
        // published, render the player before play() starts so the first short cue can highlight.
        assertTrue(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.SYNTHESIZING,
                playerIsPlaying = false,
                progressiveTtsPlayback = true,
            ),
        )
        assertFalse(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.READY,
                playerIsPlaying = false,
                progressiveTtsPlayback = true,
            ),
        )
        assertFalse(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.SYNTHESIZING,
                playerIsPlaying = true,
                progressiveTtsPlayback = true,
            ),
        )
        assertFalse(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.READY,
                playerIsPlaying = true,
                progressiveTtsPlayback = true,
            ),
        )

        // Once subtitles have played, a seek/network rebuffer must not reopen the processing page.
        assertFalse(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.SYNTHESIZING,
                playerIsPlaying = false,
                progressiveTtsPlayback = true,
                playbackHasStarted = true,
            ),
        )
        assertFalse(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.READY,
                playerIsPlaying = false,
                progressiveTtsPlayback = true,
                playbackHasStarted = true,
            ),
        )

        // A failed YouTube subtitle seek can refresh its signed stream. Keep the already-open
        // player visible during that bounded recovery instead of switching back to the parser.
        assertFalse(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.PARSING,
                playerIsPlaying = false,
                progressiveTtsPlayback = true,
                playbackHasStarted = true,
                playbackRecoveryInProgress = true,
            ),
        )
        // Normal parsing/import workflows must still show progress even if the prior player ran.
        assertTrue(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.PARSING,
                playerIsPlaying = false,
                progressiveTtsPlayback = true,
                playbackHasStarted = true,
                playbackRecoveryInProgress = false,
            ),
        )

        // Engine startup waits and failures must not be skipped just because playback was requested.
        assertTrue(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.READY,
                playerIsPlaying = true,
                progressiveTtsPlayback = true,
                waitingForDubbingEngine = true,
            ),
        )
        assertTrue(
            VideoProcessingFlowPolicy.shouldShowProcessingPage(
                phase = Phase.ERROR,
                playerIsPlaying = true,
                progressiveTtsPlayback = true,
            ),
        )
    }
    @Test
    fun readyNotificationDoesNotClaimDubbingIsCompleteWhileEnginesAreMissing() {
        assertEquals(
            "视频可播放 · 等待转写引擎",
            VideoProcessingFlowPolicy.readyNotificationWaitTitle(
                waitingForTranscriptionEngine = true,
                waitingForDubbingEngine = false,
            ),
        )
        assertEquals(
            "视频可播放 · 等待配音引擎",
            VideoProcessingFlowPolicy.readyNotificationWaitTitle(
                waitingForTranscriptionEngine = false,
                waitingForDubbingEngine = true,
            ),
        )
        assertEquals(
            "视频可播放 · 等待转写与配音引擎",
            VideoProcessingFlowPolicy.readyNotificationWaitTitle(
                waitingForTranscriptionEngine = true,
                waitingForDubbingEngine = true,
            ),
        )
        assertEquals(
            null,
            VideoProcessingFlowPolicy.readyNotificationWaitTitle(
                waitingForTranscriptionEngine = false,
                waitingForDubbingEngine = false,
            ),
        )
    }

    @Test
    fun readyProgressNamesTheEngineThatActuallyBlocksCompletion() {
        assertEquals(
            "视频可正常播放；字幕与配音等待转写引擎就绪（详见播放页提示）",
            VideoProcessingFlowPolicy.readyProgress(
                waitingForTranscriptionEngine = true,
                waitingForDubbingEngine = false,
            ),
        )
        assertEquals(
            "视频可正常播放；中文字幕已就绪，等待配音引擎就绪（详见播放页提示）",
            VideoProcessingFlowPolicy.readyProgress(
                waitingForTranscriptionEngine = false,
                waitingForDubbingEngine = true,
            ),
        )
        assertEquals(
            "视频可正常播放；字幕等待转写引擎就绪，配音等待配音引擎就绪（详见播放页提示）",
            VideoProcessingFlowPolicy.readyProgress(
                waitingForTranscriptionEngine = true,
                waitingForDubbingEngine = true,
            ),
        )
        assertEquals("", VideoProcessingFlowPolicy.readyProgress(false, false))
    }

    @Test
    fun failureProgressPointsToTheLastActiveStageInsteadOfResettingToTheFirst() {
        assertEquals(
            3,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.WEB,
                ProcessingFlow.WEB_SPEECH_RECOGNITION,
                Phase.ERROR,
                Phase.TRANSLATING,
            ),
        )
        assertEquals(
            2,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.LOCAL,
                ProcessingFlow.LOCAL_SPEECH_RECOGNITION,
                Phase.ERROR,
                Phase.TRANSCRIBING,
            ),
        )
        assertEquals(
            1,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.LOCAL,
                ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES,
                Phase.ERROR,
                Phase.SYNTHESIZING,
            ),
        )
        assertEquals(
            0,
            VideoProcessingFlowPolicy.stageIndex(
                VideoSourceKind.WEB,
                ProcessingFlow.WEB_SPEECH_RECOGNITION,
                Phase.ERROR,
            ),
        )
    }

    @Test
    fun processingPageTitleDistinguishesSourceAndFailures() {
        assertEquals("YouTube 视频翻译配音", VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.YOUTUBE))
        assertEquals("网页视频翻译配音", VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.WEB))
        assertEquals("本地视频翻译配音", VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.LOCAL))
        assertEquals("网页视频处理失败",
            VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.WEB, failed = true))
        assertEquals("字幕处理失败",
            VideoProcessingFlowPolicy.pageTitle(VideoSourceKind.WEB, ProcessingFlow.IMPORTED_SUBTITLES, failed = true))
    }
}
