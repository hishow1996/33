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
            return if (failed) "字幕处理失败" else if (flow == ProcessingFlow.IMPORTED_SUBTITLES) "字幕导入与翻译" else "字幕配音"
        }
        val label = when (source) {
            VideoSourceKind.YOUTUBE -> "YouTube 视频"
            VideoSourceKind.WEB -> "网页视频"
            VideoSourceKind.LOCAL -> "本地视频"
            VideoSourceKind.UNKNOWN -> "视频"
        }
        return if (failed) "$label 处理失败" else "$label 处理"
    }

    fun steps(source: VideoSourceKind, flow: ProcessingFlow = ProcessingFlow.AUTO): List<String> = when (flow) {
        ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION -> listOf(
            "获取视频信息",
            "解析 YouTube 视频资源",
            "提取视频音轨",
            "语音转写并生成字幕",
            "翻译字幕",
            "生成中文配音",
        )
        ProcessingFlow.IMPORTED_SUBTITLES -> listOf(
            "读取导入字幕",
            "翻译字幕",
            "生成中文配音",
        )
        ProcessingFlow.IMPORTED_TRANSLATED_SUBTITLES -> listOf(
            "读取已翻译字幕",
            "生成中文配音",
        )
        else -> when (source) {
            VideoSourceKind.YOUTUBE -> listOf(
                "获取视频信息",
                "解析 YouTube 视频资源",
                "获取原文字幕",
                "翻译字幕",
                "生成中文配音",
            )
            VideoSourceKind.WEB -> listOf(
                "识别网页视频",
                "提取视频音轨",
                "语音转写并生成字幕",
                "翻译字幕",
                "生成中文配音",
            )
            VideoSourceKind.LOCAL -> listOf(
                "读取本地视频",
                "提取本地视频音轨",
                "语音转写并生成字幕",
                "翻译字幕",
                "生成中文配音",
            )
            VideoSourceKind.UNKNOWN -> listOf(
                "获取视频信息",
                "准备视频资源",
                "获取或生成字幕",
                "翻译字幕",
                "生成中文配音",
            )
        }
    }

    fun stageIndex(source: VideoSourceKind, flow: ProcessingFlow, phase: Phase): Int {
        if (phase == Phase.ERROR) return 0
        return when (flow) {
            ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION -> when (phase) {
                Phase.IDLE, Phase.PARSING -> 0
                Phase.DOWNLOADING_AUDIO -> 2
                Phase.TRANSCRIBING -> 3
                Phase.TRANSLATING -> 4
                Phase.SYNTHESIZING -> 5
                Phase.READY -> 6
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
            else -> when (phase) {
                Phase.IDLE, Phase.PARSING -> 0
                Phase.DOWNLOADING_AUDIO -> 1
                Phase.TRANSCRIBING -> 2
                Phase.TRANSLATING -> 3
                Phase.SYNTHESIZING -> 4
                Phase.READY -> 5
                Phase.ERROR -> 0
            }
        }
    }

    fun transcriptionStageIndex(source: VideoSourceKind, flow: ProcessingFlow): Int =
        if (flow == ProcessingFlow.YOUTUBE_SPEECH_RECOGNITION) 3 else 2

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
            Phase.IDLE -> "准备处理视频"
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
                VideoSourceKind.YOUTUBE -> "正在识别语音并生成字幕"
                VideoSourceKind.WEB -> "正在转写网页视频语音"
                VideoSourceKind.LOCAL -> "正在转写本地视频语音"
                VideoSourceKind.UNKNOWN -> "正在识别语音并生成字幕"
            }
            Phase.TRANSLATING -> "正在翻译字幕"
            Phase.SYNTHESIZING -> "正在生成中文配音"
            Phase.READY -> "处理已完成"
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
                VideoSourceKind.YOUTUBE -> "正在获取视频信息并检查字幕来源"
                VideoSourceKind.WEB -> "正在确认网页视频资源，准备提取音轨"
                VideoSourceKind.LOCAL -> "正在检查本地视频文件和音轨"
                VideoSourceKind.UNKNOWN -> "正在检查视频信息和可用资源"
            }
            Phase.DOWNLOADING_AUDIO -> when (source) {
                VideoSourceKind.YOUTUBE -> "当前需要语音转写，正在准备识别所需音轨"
                VideoSourceKind.WEB -> "正在提取并缓存网页视频音轨，不默认下载完整视频"
                VideoSourceKind.LOCAL -> "正在从本地视频提取音轨"
                VideoSourceKind.UNKNOWN -> "正在准备语音识别所需音轨"
            }
            Phase.TRANSCRIBING -> when (source) {
                VideoSourceKind.YOUTUBE -> "正在把语音转换为带时间轴的原文字幕"
                VideoSourceKind.WEB -> "正在把网页视频音轨转写为带时间轴的原文字幕"
                VideoSourceKind.LOCAL -> "正在把本地视频音轨转写为带时间轴的原文字幕"
                VideoSourceKind.UNKNOWN -> "正在生成带时间轴的原文字幕"
            }
            Phase.TRANSLATING -> "正在将原文字幕翻译成中文"
            Phase.SYNTHESIZING -> "正在按字幕时间轴生成中文配音"
            Phase.READY -> "视频、字幕和配音已准备完成"
            Phase.ERROR -> error?.takeIf { it.isNotBlank() } ?: "处理失败，请重试或导入字幕文件"
        }
    }
}
