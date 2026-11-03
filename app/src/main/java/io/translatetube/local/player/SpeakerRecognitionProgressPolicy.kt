package io.translatetube.local.player

/** A single visible stage of speaker recognition. The UI never renders the full stage list. */
enum class SpeakerRecognitionStage(val title: String, val activeLabel: String) {
    PREPARING_AUDIO("音频准备", "正在准备音频…"),
    AUDIO_DECODING("音频解码", "正在解码音频…"),
    INITIALIZING_VOICE_ENGINE("初始化配音引擎", "正在初始化配音引擎…"),
    CHECKING_MODELS("模型检查", "正在检查人物模型…"),
    DOWNLOADING_MODELS("下载人物模型", "正在下载人物模型…"),
    EXTRACTING_MODELS("解压人物模型", "正在解压人物模型…"),
    INITIALIZING_MODEL("初始化识别引擎", "正在初始化识别引擎…"),
    ANALYZING_AUDIO("人物声音分析", "正在分析人物声音…"),
    LINKING_SUBTITLES("关联字幕与人物", "正在关联字幕与人物…"),
    APPLYING_VOICES("应用人物音色", "正在更新人物音色…"),
}

/** Structured progress emitted by the worker; percentages are only set for measured work. */
data class SpeakerRecognitionProgressUpdate(
    val stage: SpeakerRecognitionStage,
    val message: String,
    val progressPercent: Int? = null,
    val completedUnits: Long? = null,
    val totalUnits: Long? = null,
)

object SpeakerRecognitionProgressPolicy {
    fun percentForCount(completedCount: Long, totalCount: Long): Int? {
        if (totalCount <= 0L) return null
        val completed = completedCount.coerceIn(0L, totalCount)
        return (completed * 100L / totalCount).toInt().coerceIn(0, 100)
    }

    /** A single long inference window has no measurable intra-window callback, so stay indeterminate. */
    fun analysisChunkStarted(currentChunk: Long, totalChunks: Long): SpeakerRecognitionProgressUpdate {
        val total = totalChunks.coerceAtLeast(1L)
        val current = currentChunk.coerceIn(1L, total)
        val completed = current - 1L
        return SpeakerRecognitionProgressUpdate(
            stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
            message = "正在分析音频分段 $current/$total · 已完成 $completed/$total 段…",
            progressPercent = if (total > 1L) percentForCount(completed, total) else null,
            completedUnits = completed,
            totalUnits = total,
        )
    }

    fun analysisChunkCompleted(completedChunks: Long, totalChunks: Long): SpeakerRecognitionProgressUpdate {
        val total = totalChunks.coerceAtLeast(1L)
        val completed = completedChunks.coerceIn(0L, total)
        return SpeakerRecognitionProgressUpdate(
            stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
            message = "已完成音频分段 $completed/$total",
            progressPercent = percentForCount(completed, total),
            completedUnits = completed,
            totalUnits = total,
        )
    }
}