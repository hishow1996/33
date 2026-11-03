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
    /** Distinguishes consecutive downloads that share the same visible stage. */
    val progressItem: String? = null,
)

object SpeakerRecognitionProgressPolicy {

    /** Audio fetched for speaker diarization is secondary player work, not stage-one processing. */
    fun audioAcquisitionProgress(message: String): SpeakerRecognitionProgressUpdate {
        val percent = Regex("""(\\d+(?:\\.\\d+)?)%""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.toInt()
            ?.coerceIn(0, 100)
        return SpeakerRecognitionProgressUpdate(
            stage = SpeakerRecognitionStage.PREPARING_AUDIO,
            message = message,
            progressPercent = percent,
            progressItem = "音轨",
        )
    }

    /** Prevent secondary speaker-audio work from changing the active player's five-stage state. */
    fun preservePlaybackState(
        current: PlayerUiState,
        requested: PlayerUiState,
        speakerRecognitionOnly: Boolean,
    ): PlayerUiState {
        if (!speakerRecognitionOnly || current.key.isBlank() || current.key != requested.key) {
            return requested
        }
        val taskMessage = requested.progress.takeIf {
            it.isNotBlank() && it != current.progress
        }
        val taskProgress = taskMessage?.let(::audioAcquisitionProgress)
        return requested.copy(
            phase = current.phase,
            progress = current.progress,
            audioProgress = current.audioProgress,
            transcriptionProgressPercent = current.transcriptionProgressPercent,
            speakerRecognition = if (taskProgress != null) {
                current.speakerRecognition.copy(
                    stage = taskProgress.stage,
                    message = taskProgress.message,
                    progressPercent = taskProgress.progressPercent,
                    progressItem = taskProgress.progressItem,
                )
            } else current.speakerRecognition,
        )
    }

    fun percentForCount(completedCount: Long, totalCount: Long): Int? {
        if (totalCount <= 0L) return null
        val completed = completedCount.coerceIn(0L, totalCount)
        return (completed * 100L / totalCount).toInt().coerceIn(0, 100)
    }

    /** Before the optional native callback starts, a single window remains indeterminate. */
    fun analysisChunkStarted(currentChunk: Long, totalChunks: Long): SpeakerRecognitionProgressUpdate {
        val total = totalChunks.coerceAtLeast(1L)
        val current = currentChunk.coerceIn(1L, total)
        val completed = current - 1L
        return SpeakerRecognitionProgressUpdate(
            stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
            message = "正在分析音频分段…",
            progressPercent = if (total > 1L) percentForCount(completed, total) else null,
            completedUnits = completed,
            totalUnits = total,
        )
    }

    fun analysisInferenceProgress(
        completedChunks: Long,
        totalChunks: Long,
        processedInferenceUnits: Long,
        totalInferenceUnits: Long,
    ): SpeakerRecognitionProgressUpdate {
        val total = totalChunks.coerceAtLeast(1L)
        val completed = completedChunks.coerceIn(0L, total)
        val inferenceTotal = totalInferenceUnits.coerceAtLeast(1L)
        val inferenceCompleted = processedInferenceUnits.coerceIn(0L, inferenceTotal)
        val withinChunkFraction = inferenceCompleted.toDouble() / inferenceTotal.toDouble()
        val percent = (((completed.toDouble() + withinChunkFraction) / total.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        return SpeakerRecognitionProgressUpdate(
            stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
            message = "正在分析当前音频分段…",
            progressPercent = percent,
            completedUnits = completed,
            totalUnits = total,
            progressItem = "模型内部分析",
        )
    }

    fun analysisChunkCompleted(completedChunks: Long, totalChunks: Long): SpeakerRecognitionProgressUpdate {
        val total = totalChunks.coerceAtLeast(1L)
        val completed = completedChunks.coerceIn(0L, total)
        return SpeakerRecognitionProgressUpdate(
            stage = SpeakerRecognitionStage.ANALYZING_AUDIO,
            message = "已完成音频分析",
            progressPercent = percentForCount(completed, total),
            completedUnits = completed,
            totalUnits = total,
        )
    }
}