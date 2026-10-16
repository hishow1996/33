package io.translatetube.local.player

/** Source-aware progress copy shared by YouTube, webpage and local-video sessions. */
enum class VideoSourceKind {
    YOUTUBE,
    WEB,
    LOCAL,
    UNKNOWN,
}

object VideoProcessingFlowPolicy {
    fun sourceKind(sessionKey: String): VideoSourceKind = when {
        sessionKey.startsWith("youtube:", ignoreCase = true) -> VideoSourceKind.YOUTUBE
        sessionKey.startsWith("remote:", ignoreCase = true) -> VideoSourceKind.WEB
        sessionKey.startsWith("local:", ignoreCase = true) -> VideoSourceKind.LOCAL
        else -> VideoSourceKind.UNKNOWN
    }

    fun pageTitle(source: VideoSourceKind, failed: Boolean = false): String {
        val label = when (source) {
            VideoSourceKind.YOUTUBE -> "YouTube 视频"
            VideoSourceKind.WEB -> "网页视频"
            VideoSourceKind.LOCAL -> "本地视频"
            VideoSourceKind.UNKNOWN -> "视频"
        }
        return if (failed) "$label 处理失败" else "$label 处理"
    }

    fun steps(source: VideoSourceKind): List<String> = when (source) {
        VideoSourceKind.YOUTUBE -> listOf(
            "获取视频信息",
            "解析 YouTube 视频资源",
            "获取或生成原文字幕",
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

    fun mainTitle(
        source: VideoSourceKind,
        phase: Phase,
        isPaused: Boolean = false,
        waitingForTranscriptionEngine: Boolean = false,
    ): String {
        if (waitingForTranscriptionEngine && phase != Phase.ERROR) return "等待转写引擎就绪"
        if (isPaused) return "处理已暂停"
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

    fun defaultDetail(source: VideoSourceKind, phase: Phase, error: String? = null): String = when (phase) {
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
