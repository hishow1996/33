package io.translatetube.local.player

/** Source-aware progress copy shared by YouTube, webpage and local-video sessions. */
enum class VideoSourceKind {
    YOUTUBE,
    WEB,
    LOCAL,
    UNKNOWN,
}

/** The actual path selected by the service; AUTO is used while YouTube caption lookup is unresolved. */
enum class ProcessingFlow {
    AUTO,
    YOUTUBE_SUBTITLES,
    YOUTUBE_SPEECH_RECOGNITION,
    WEB_SPEECH_RECOGNITION,
    LOCAL_SPEECH_RECOGNITION,
    IMPORTED_SUBTITLES,
    IMPORTED_TRANSLATED_SUBTITLES,
}

object VideoProcessingFlowPolicy {
    fun sourceKind(sessionKey: String): VideoSourceKind = when {
        sessionKey.startsWith("youtube:", ignoreCase = true) -> VideoSourceKind.YOUTUBE
        sessionKey.startsWith("remote:", ignoreCase = true) -> VideoSourceKind.WEB
        sessionKey.startsWith("local:", ignoreCase = true) -> VideoSourceKind.LOCAL
        else -> VideoSourceKind.UNKNOWN
    }

    fun pageTitle(source: VideoSourceKind, flow: ProcessingFlow = ProcessingFlow.AUTO, failed: Boolean = false): String {
        if (flow == ProcessingFlow.IMPORTED_SUBTITLES || flow == ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES) {
            return if (failed) "字幕处理失败" else if (flow == ProcessingFlow.IMPORTED_SUBTITLES) "字幕翻译配音" else "已翻译字幕配音"
        }
        val label = when (source) {
            VideoSourceKind.YOUTUBE -> "YouTube 视频"
            VideoSourceKind.WEB -> "网页视频"
            VideoSourceKind.LOCAL -> "本地视频"
            VideoSourceKind.UNKNOWN -> "视频"
        }
        return if (failed) "${label}处理失败" else "${label}翻译配音"
    }

    fun steps(source: VideoSourceKind, flow: ProcessingFlow = ProcessingFlow.AUTO): List<String> = when (flow) {
        ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION -> listOf(
            "获取视频信息",
            "查找并读取可用原文字幕",
            "提取视频音轨",
            "转写原文并生成时间轴字幕",
            "将原文字幕翻译成中文",
            "准备播放资源并按时间轴生成中文配音",
        )
        ProcessingFlow.YOUTUBE_SUBTITLES -> listOf(
            "获取视频信息",
            "查找可用字幕轨",
            "读取原文字幕与时间轴",
            "将原文字幕翻译成中文",
            "准备播放资源并按时间轴生成中文配音",
        )
        ProcessingFlow.IMPORTED_SUBTITLES -> listOf(
            "读取导入字幕并检查时间轴",
            "将原文字幕翻译成中文",
            "按时间轴生成中文配音",
        )
        ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES -> listOf(
            "读取已翻译字幕并检查时间轴",
            "按时间轴生成中文配音",
        )
        else -> when (source) {
            VideoSourceKind.YOUTUBE -> listOf(
                "获取视频信息",
                "查找可用字幕轨",
                "读取原文字幕与时间轴",
                "将原文字幕翻译成中文",
                "准备播放资源并按时间轴生成中文配音",
            )
            VideoSourceKind.WEB -> listOf(
                "识别网页视频",
                "提取视频音轨",
                "转写原文并生成时间轴字幕",
                "将原文字幕翻译成中文",
                "按时间轴生成中文配音",
            )
            VideoSourceKind.LOCAL -> listOf(
                "读取本地视频",
                "提取本地视频音轨",
                "转写原文并生成时间轴字幕（本地视频）",
                "将原文字幕翻译成中文",
                "按时间轴生成中文配音",
            )
            VideoSourceKind.UNKNOWN -> listOf(
                "获取视频信息",
                "准备视频资源",
                "获取或生成字幕",
                "将原文字幕翻译成中文",
                "按时间轴生成中文配音",
            )
        }
    }

    fun stageIndex(
        source: VideoSourceKind,
        flow: ProcessingFlow,
        phase: Phase,
        failedPhase: Phase? = null,
        progress: String = "",
        waitingForTranscriptionEngine: Boolean = false,
    ): Int {
        if (phase == Phase.ERROR) {
            val stoppedAt = failedPhase?.takeIf { it != Phase.ERROR } ?: return 0
            return stageIndex(
                source = source,
                flow = flow,
                phase = stoppedAt,
                progress = progress,
            )
        }

        val resolved = when (flow) {
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION -> when (phase) {
                Phase.IDLE -> 0
                Phase.PARSING -> youtubeSubtitleLookupStage(progress, fallbackStage = 0)
                Phase.DOWNLOADING_AUDIO -> 2
                Phase.TRANSCRIBING -> 3
                Phase.TRANSLATING -> 4
                Phase.SYNTHESIZING -> 5
                Phase.READY -> 6
                Phase.ERROR -> 0
            }
            ProcessingFlow.YOUTUBE_SUBTITLES -> when (phase) {
                Phase.IDLE -> 0
                Phase.PARSING -> youtubeSubtitleStage(progress)
                Phase.DOWNLOADING_AUDIO -> 1
                Phase.TRANSCRIBING -> 2
                Phase.TRANSLATING -> 3
                Phase.SYNTHESIZING -> 4
                Phase.READY -> 5
                Phase.ERROR -> 0
            }
            ProcessingFlow.IMPORTED_SUBTITLES -> when (phase) {
                Phase.IDLE, Phase.PARSING -> 0
                Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING -> 0
                Phase.TRANSLATING -> 1
                Phase.SYNTHESIZING -> 2
                Phase.READY -> 3
                Phase.ERROR -> 0
            }
            ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES -> when (phase) {
                Phase.IDLE, Phase.PARSING, Phase.DOWNLOADING_AUDIO, Phase.TRANSCRIBING, Phase.TRANSLATING -> 0
                Phase.SYNTHESIZING -> 1
                Phase.READY -> 2
                Phase.ERROR -> 0
            }
            ProcessingFlow.AUTO -> if (source == VideoSourceKind.YOUTUBE) {
                when (phase) {
                    Phase.IDLE -> 0
                    Phase.PARSING -> youtubeSubtitleStage(progress)
                    Phase.DOWNLOADING_AUDIO -> 1
                    Phase.TRANSCRIBING -> 2
                    Phase.TRANSLATING -> 3
                    Phase.SYNTHESIZING -> 4
                    Phase.READY -> 5
                    Phase.ERROR -> 0
                }
            } else genericStageIndex(phase)
            else -> genericStageIndex(phase)
        }
        return if (waitingForTranscriptionEngine && phase != Phase.ERROR) {
            minOf(resolved, transcriptionStageIndex(source, flow))
        } else {
            resolved
        }
    }

    private fun youtubeSubtitleLookupStage(progress: String, fallbackStage: Int): Int = when {
        progress.contains("查找可用字幕轨") -> 1
        progress.contains("检查本地字幕文件与缓存") -> 1
        progress.contains("下载并读取字幕文件") -> 1
        else -> fallbackStage
    }

    private fun youtubeSubtitleStage(progress: String): Int = when {
        progress.contains("查找可用字幕轨") -> 1
        progress.contains("检查本地字幕文件与缓存") -> 2
        progress.contains("下载并读取字幕文件") -> 2
        else -> 0
    }

    private fun genericStageIndex(phase: Phase): Int = when (phase) {
        Phase.IDLE, Phase.PARSING -> 0
        Phase.DOWNLOADING_AUDIO -> 1
        Phase.TRANSCRIBING -> 2
        Phase.TRANSLATING -> 3
        Phase.SYNTHESIZING -> 4
        Phase.READY -> 5
        Phase.ERROR -> 0
    }

    fun isCompleted(
        phase: Phase,
        waitingForTranscriptionEngine: Boolean = false,
        waitingForDubbingEngine: Boolean = false,
        dubbingHasErrors: Boolean = false,
    ): Boolean = phase == Phase.READY &&
        !waitingForTranscriptionEngine &&
        !waitingForDubbingEngine &&
        !dubbingHasErrors

    /** A READY player may still be preparing remaining dubbed cues in the background. */
    fun isDubbingPreparationIncomplete(
        dubbingOn: Boolean,
        totalLines: Int,
        preparedCount: Int,
    ): Boolean = dubbingOn && totalLines > 0 && preparedCount.coerceAtLeast(0) < totalLines

    /**
     * Live Android System TTS speaks directly on the subtitle timeline and does not need a PCM
     * generation queue. Other TTS engines must keep the preparation page visible until their
     * subtitle cues are ready, even if the video surface has already started playing.
     */
    fun shouldShowProcessingPage(
        phase: Phase,
        waitingForTranscriptionEngine: Boolean = false,
        waitingForDubbingEngine: Boolean = false,
        playerIsPlaying: Boolean = false,
        systemTtsLivePlayback: Boolean = false,
        dubbingPreparationIncomplete: Boolean = false,
    ): Boolean {
        if (phase == Phase.ERROR || waitingForTranscriptionEngine || waitingForDubbingEngine) {
            return true
        }
        if (
            systemTtsLivePlayback &&
            playerIsPlaying &&
            phase in setOf(Phase.SYNTHESIZING, Phase.READY)
        ) {
            // Keep the page until playback really starts, then show the player without waiting
            // for a PCM queue that Android System TTS never creates.
            return false
        }
        if (systemTtsLivePlayback && phase == Phase.READY && !playerIsPlaying) {
            // Avoid a one-frame flash of the player between READY and ExoPlayer actually starting.
            return true
        }
        // Kokoro/Piper and other synthesis engines need their speech cues prepared first.
        return phase != Phase.READY || (!systemTtsLivePlayback && dubbingPreparationIncomplete)
    }

    fun readyNotificationFailureTitle(dubbingHasErrors: Boolean): String? =
        if (dubbingHasErrors) "配音部分失败 · 失败字幕已恢复原声" else null

    fun transcriptionStageIndex(source: VideoSourceKind, flow: ProcessingFlow): Int =
        if (flow == ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION) 3 else 2

    /** Notification title for a READY player whose translation or dubbing setup is still blocked. */
    fun readyNotificationWaitTitle(
        waitingForTranscriptionEngine: Boolean,
        waitingForDubbingEngine: Boolean,
    ): String? = when {
        waitingForTranscriptionEngine && waitingForDubbingEngine ->
            "视频可播放 · 等待转写与配音引擎"
        waitingForTranscriptionEngine -> "视频可播放 · 等待转写引擎"
        waitingForDubbingEngine -> "视频可播放 · 等待配音引擎"
        else -> null
    }

    /** Keep READY-state status text aligned with the engine that actually blocks completion. */
    fun readyProgress(
        waitingForTranscriptionEngine: Boolean,
        waitingForDubbingEngine: Boolean,
    ): String = when {
        waitingForTranscriptionEngine && waitingForDubbingEngine ->
            "视频可正常播放；字幕等待转写引擎就绪，配音等待配音引擎就绪（详见播放页提示）"
        waitingForTranscriptionEngine ->
            "视频可正常播放；字幕与配音等待转写引擎就绪（详见播放页提示）"
        waitingForDubbingEngine ->
            "视频可正常播放；中文字幕已就绪，等待配音引擎就绪（详见播放页提示）"
        else -> ""
    }

    fun mainTitle(
        source: VideoSourceKind,
        phase: Phase,
        flow: ProcessingFlow = ProcessingFlow.AUTO,
        isPaused: Boolean = false,
        waitingForTranscriptionEngine: Boolean = false,
    ): String {
        if (waitingForTranscriptionEngine && phase != Phase.ERROR) return "等待转写引擎就绪"
        if (isPaused) return "处理已暂停"
        if (phase == Phase.PARSING) {
            when (flow) {
                ProcessingFlow.IMPORTED_SUBTITLES -> return "正在读取导入字幕"
                ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES -> return "正在读取已翻译字幕"
                else -> Unit
            }
        }
        return when (phase) {
            Phase.IDLE -> "准备翻译视频并生成配音"
            Phase.PARSING -> when (source) {
                VideoSourceKind.YOUTUBE -> "正在解析 YouTube 视频"
                VideoSourceKind.WEB -> "正在识别网页视频"
                VideoSourceKind.LOCAL -> "正在读取本地视频"
                VideoSourceKind.UNKNOWN -> "正在检查视频信息"
            }
            Phase.DOWNLOADING_AUDIO -> when (source) {
                VideoSourceKind.YOUTUBE -> "正在提取 YouTube 视频音轨"
                VideoSourceKind.WEB -> "正在提取网页视频音轨"
                VideoSourceKind.LOCAL -> "正在提取本地视频音轨"
                VideoSourceKind.UNKNOWN -> "正在提取视频音轨"
            }
            Phase.TRANSCRIBING -> when (source) {
                VideoSourceKind.YOUTUBE -> "正在转写原文并生成时间轴字幕"
                VideoSourceKind.WEB -> "正在转写网页视频原文并生成时间轴字幕"
                VideoSourceKind.LOCAL -> "正在转写本地视频原文并生成时间轴字幕"
                VideoSourceKind.UNKNOWN -> "正在转写原文并生成时间轴字幕"
            }
            Phase.TRANSLATING -> "正在将原文字幕翻译成中文"
            Phase.SYNTHESIZING -> "正在按字幕时间轴生成中文配音"
            Phase.READY -> "视频、字幕与配音已准备完成"
            Phase.ERROR -> "视频处理失败"
        }
    }

    fun defaultDetail(
        source: VideoSourceKind,
        flow: ProcessingFlow,
        phase: Phase,
        error: String? = null,
    ): String {
        if (phase == Phase.PARSING) {
            when (flow) {
                ProcessingFlow.IMPORTED_SUBTITLES -> return "正在读取字幕文件并检查时间轴"
                ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES -> return "正在读取已翻译字幕并检查时间轴"
                else -> Unit
            }
        }
        return when (phase) {
            Phase.IDLE -> "等待开始处理"
            Phase.PARSING -> when (source) {
                VideoSourceKind.YOUTUBE -> "优先查找原文字幕；找不到时会转向音轨转写"
                VideoSourceKind.WEB -> "正在确认网页视频资源，准备提取音轨并转写原文"
                VideoSourceKind.LOCAL -> "正在检查本地视频信息，准备提取音轨并转写原文"
                VideoSourceKind.UNKNOWN -> "正在检查视频信息和可用字幕/音轨"
            }
            Phase.DOWNLOADING_AUDIO -> when (source) {
                VideoSourceKind.YOUTUBE -> "当前需要语音转写，正在准备识别所需音轨"
                VideoSourceKind.WEB -> "正在提取并缓存网页视频音轨，不默认下载完整视频"
                VideoSourceKind.LOCAL -> "正在从本地视频提取音轨"
                VideoSourceKind.UNKNOWN -> "正在准备语音识别所需音轨"
            }
            Phase.TRANSCRIBING -> when (source) {
                VideoSourceKind.YOUTUBE -> "识别结果会保留时间戳，随后翻译成中文"
                VideoSourceKind.WEB -> "正在把网页视频音轨转写成带时间轴的原文字幕，下一步翻译成中文"
                VideoSourceKind.LOCAL -> "正在把本地视频音轨转写成带时间轴的原文字幕，下一步翻译成中文"
                VideoSourceKind.UNKNOWN -> "正在生成带时间轴的原文字幕，下一步翻译成中文"
            }
            Phase.TRANSLATING -> "按批次把原文字幕翻译成中文，保留每条字幕的起止时间"
            Phase.SYNTHESIZING -> "根据字幕时间轴逐句准备中文配音"
            Phase.READY -> "视频、字幕和配音已准备完成"
            Phase.ERROR -> error?.takeIf { it.isNotBlank() } ?: "处理失败，请重试或导入字幕文件"
        }
    }
}
