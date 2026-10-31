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

    /**
     * Video playback must not wait for the entire PCM queue. The player enters after a small
     * startup buffer is ready; DubbingEngine continues synthesizing the remaining cues in the
     * background and promotes upcoming cues as the playhead advances.
     */
    fun requiresCompleteDubbingBeforePlayback(source: VideoSourceKind): Boolean = when (source) {
        VideoSourceKind.YOUTUBE,
        VideoSourceKind.WEB,
        VideoSourceKind.LOCAL,
        VideoSourceKind.UNKNOWN -> false
    }

    /** Every source may enter progressive playback once a TTS engine and startup buffer are ready. */
    fun allowsProgressiveTtsPlayback(source: VideoSourceKind, hasTtsEngine: Boolean): Boolean =
        hasTtsEngine && !requiresCompleteDubbingBeforePlayback(source)

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
            "解析视频",
            "提取视频音轨",
            "转写原文并生成时间轴字幕",
            "将原文字幕翻译成中文",
            "按时间轴生成中文配音",
        )
        ProcessingFlow.YOUTUBE_SUBTITLES -> listOf(
            "解析视频",
            "查找可用字幕轨",
            "读取原文字幕与时间轴",
            "将原文字幕翻译成中文",
            "按时间轴生成中文配音",
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
                "解析视频",
                "查找可用字幕轨",
                "读取原文字幕与时间轴",
                "将原文字幕翻译成中文",
                "按时间轴生成中文配音",
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
                "转写原文并生成时间轴字幕",
                "将原文字幕翻译成中文",
                "按时间轴生成中文配音",
            )
            VideoSourceKind.UNKNOWN -> listOf(
                "解析视频",
                "准备视频资源",
                "转写原文并生成时间轴字幕",
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
                Phase.IDLE, Phase.PARSING -> 0
                Phase.DOWNLOADING_AUDIO -> 1
                Phase.TRANSCRIBING -> 2
                Phase.TRANSLATING -> 3
                Phase.SYNTHESIZING -> 4
                Phase.READY -> 5
                Phase.ERROR -> 0
            }
            ProcessingFlow.YOUTUBE_SUBTITLES -> when (phase) {
                // Finding/reading captions is part of video parsing, not audio extraction or ASR.
                Phase.IDLE, Phase.PARSING -> 0
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
                    // Caption lookup remains visible as a detail of the first conceptual stage.
                    Phase.IDLE, Phase.PARSING -> 0
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
     * Progressive TTS mode: once video playback starts, show the player while later subtitle
     * cues continue generating PCM in the background. Startup still waits briefly for the first
     * usable cue; failures and unavailable engines keep the processing page visible.
     */
    fun shouldShowProcessingPage(
        phase: Phase,
        waitingForTranscriptionEngine: Boolean = false,
        waitingForDubbingEngine: Boolean = false,
        playerIsPlaying: Boolean = false,
        progressiveTtsPlayback: Boolean = false,
        dubbingPreparationIncomplete: Boolean = false,
        playbackHasStarted: Boolean = false,
        playbackRecoveryInProgress: Boolean = false,
    ): Boolean {
        // Remember that the player has already been shown after the first cue started. A seek
        // can invalidate a YouTube signed URL, so the service refreshes the stream and temporarily
        // marks its phase as PARSING. That is playback recovery, not a new video-processing run.
        val playbackHasEnteredPlayer = playbackHasStarted || playerIsPlaying
        if (
            progressiveTtsPlayback &&
            playbackHasEnteredPlayer &&
            playbackRecoveryInProgress &&
            phase == Phase.PARSING &&
            !waitingForTranscriptionEngine &&
            !waitingForDubbingEngine
        ) {
            return false
        }
        if (phase == Phase.ERROR || waitingForTranscriptionEngine || waitingForDubbingEngine) {
            return true
        }
        if (
            progressiveTtsPlayback &&
            playbackHasEnteredPlayer &&
            phase in setOf(Phase.SYNTHESIZING, Phase.READY)
        ) {
            // Remaining PCM cues may continue synthesizing while the player stays visible.
            return false
        }
        if (progressiveTtsPlayback && phase == Phase.READY) {
            // READY means the first usable speech buffer has been handled. Render the player
            // before asking ExoPlayer to begin so the first short subtitle cue is not spoken
            // entirely behind the processing screen before its active-row effect can appear.
            return false
        }
        // In non-progressive mode, all speech cues must be prepared before leaving this page.
        return phase != Phase.READY || (!progressiveTtsPlayback && dubbingPreparationIncomplete)
    }

    /**
     * A mounted/buffering player must not let a transient loading hint hide stage 5 before the
     * initial dubbing buffer is ready and playback has actually entered the player.
     */
    @Suppress("UNUSED_PARAMETER")
    fun playbackHintMayKeepPlayerVisible(
        phase: Phase,
        progressiveTtsPlayback: Boolean,
        playbackHasEnteredPlayer: Boolean,
    ): Boolean = phase != Phase.SYNTHESIZING || playbackHasEnteredPlayer

    fun readyNotificationFailureTitle(dubbingHasErrors: Boolean): String? =
        if (dubbingHasErrors) "配音部分失败 · 失败字幕已恢复原声" else null

    fun transcriptionStageIndex(source: VideoSourceKind, flow: ProcessingFlow): Int = 2

    fun translationBatchLabel(currentBatch: Int, totalBatches: Int, retryCount: Int): String? {
        if (totalBatches <= 0) return null
        val current = currentBatch.coerceIn(0, totalBatches)
        val batch = if (current == 0) "共 ${totalBatches} 批" else "第 ${current}/${totalBatches} 批"
        return if (retryCount > 0) "$batch · 重试第 $retryCount 次" else batch
    }

    fun completionPercent(completed: Int, total: Int): Int? {
        if (total <= 0) return null
        val safeCompleted = completed.coerceIn(0, total)
        return ((safeCompleted.toLong() * 100L) / total.toLong()).toInt()
    }

    /** A single, consistent display format for measured stage counters. */
    fun progressCounterLabel(completed: Int, total: Int, unit: String): String? {
        val normalizedUnit = unit.trim()
        if (total <= 0 || normalizedUnit.isBlank()) return null
        val safeCompleted = completed.coerceIn(0, total)
        val percent = completionPercent(safeCompleted, total) ?: return null
        return "$safeCompleted/$total $normalizedUnit · $percent%"
    }

    /**
     * Compact status for YouTube caption discovery, shown inside the video-parsing stage.
     * It deliberately does not turn caption lookup/read operations into audio/ASR stages.
     */
    fun youtubeSubtitleDetail(
        progress: String,
        subtitleTrackStatus: String,
        availableTrackCount: Int,
    ): String? {
        val message = progress.trim()
        val status = subtitleTrackStatus.trim()
        return when {
            message.contains("查找可用字幕轨") -> "正在查找字幕轨"
            message.contains("检查本地字幕文件与缓存") -> "正在检查字幕缓存"
            message.contains("下载并读取字幕文件") -> "正在读取字幕文件"
            message.contains("回退") -> "已切换到备用字幕"
            message.contains("切换") -> "正在切换字幕语言"
            availableTrackCount > 0 -> "已发现 $availableTrackCount 条字幕轨"
            status.contains("未找到") -> "未发现字幕轨"
            status.contains("暂时无法读取") -> "字幕列表暂不可用"
            status.startsWith("找到") -> "已发现字幕轨"
            message.contains("解析") -> "正在解析视频信息"
            else -> null
        }
    }

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
