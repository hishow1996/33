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

/** Presentation-only model shared by all speaker-recognition stages and media sources. */
data class SpeakerRecognitionProgressDisplay(
    val message: String,
    val secondaryDetail: String? = null,
)

object SpeakerRecognitionProgressPolicy {

    private val byteProgressPattern = Regex(
        """\d+(?:\.\d+)?\s*(?:TB|GB|MB|KB|B)(?:\s*/\s*(?:预计约\s*)?\d+(?:\.\d+)?\s*(?:TB|GB|MB|KB|B))?""",
        RegexOption.IGNORE_CASE,
    )
    private val percentagePattern = Regex("""\d+(?:\.\d+)?\s*%""")

    /**
     * Produces a consistent status/detail split for all sources and processing stages.
     * Numeric progress is never derived here; it remains owned by the worker updates.
     */
    fun display(state: SpeakerRecognitionUiState): SpeakerRecognitionProgressDisplay {
        if (!state.error.isNullOrBlank()) {
            return SpeakerRecognitionProgressDisplay(state.error.trim())
        }
        if (state.phase == SpeakerRecognitionPhase.SUCCEEDED) {
            val people = state.personCount.takeIf { it > 0 }?.let { it.toString() + " 位人物" }
            return SpeakerRecognitionProgressDisplay("识别完成", people)
        }
        if (state.phase == SpeakerRecognitionPhase.CANCELLED) {
            return SpeakerRecognitionProgressDisplay("人物识别已取消")
        }

        val rawMessage = state.message.ifBlank { state.activeLabel }
        val base = displayMessage(rawMessage)

        return when (state.stage) {
            SpeakerRecognitionStage.PREPARING_AUDIO -> base

            SpeakerRecognitionStage.DOWNLOADING_MODELS -> {
                val status = base.message
                    .replace(Regex("""^下载中(?=\S)"""), "正在下载")
                    .replace(Regex("""(?:·\s*)?已下载$"""), "")
                    .trim()
                val downloaded = state.completedUnits?.takeIf { it > 0L }
                val total = state.totalUnits?.takeIf { it > 0L }
                val detail = when {
                    downloaded != null && total != null ->
                        formatCompactBytes(downloaded) + " / " + formatCompactBytes(total)
                    downloaded != null -> "已下载 " + formatCompactBytes(downloaded)
                    else -> base.secondaryDetail
                }
                SpeakerRecognitionProgressDisplay(status, detail)
            }

            SpeakerRecognitionStage.ANALYZING_AUDIO -> {
                val completed = state.completedUnits
                val total = state.totalUnits?.takeIf { it > 0L }
                val detail = if (completed != null && total != null) {
                    val safeCompleted = completed.coerceIn(0L, total)
                    if (
                        state.progressItem == "模型内部分析" ||
                        state.message.contains("正在分析")
                    ) {
                        "当前分段 " + (safeCompleted + 1L).coerceAtMost(total) + "/" + total
                    } else {
                        "已分析 " + safeCompleted + "/" + total + " 段"
                    }
                } else {
                    base.secondaryDetail
                }
                SpeakerRecognitionProgressDisplay(base.message, detail)
            }

            SpeakerRecognitionStage.LINKING_SUBTITLES -> {
                val completed = state.completedUnits
                val total = state.totalUnits?.takeIf { it > 0L }
                val detail = if (completed != null && total != null) {
                    val people = state.personCount.takeIf { it > 0 }?.let { " · " + it + " 位人物" }.orEmpty()
                    "已关联 " + completed.coerceIn(0L, total) + "/" + total + " 行" + people
                } else {
                    state.personCount.takeIf { it > 0 }?.let { it.toString() + " 位人物" }
                }
                SpeakerRecognitionProgressDisplay(base.message, detail ?: base.secondaryDetail)
            }

            SpeakerRecognitionStage.APPLYING_VOICES -> {
                val people = state.personCount.takeIf { it > 0 }?.let { it.toString() + " 位人物" }
                SpeakerRecognitionProgressDisplay(base.message, people ?: base.secondaryDetail)
            }

            else -> base
        }
    }

    /**
     * Removes details already displayed beside/below the progress bar. The percentage is
     * deliberately not recomputed here; it remains the worker's measured value.
     */
    fun displayMessage(message: String): SpeakerRecognitionProgressDisplay {
        val raw = message.trim()
        if (raw.isBlank()) return SpeakerRecognitionProgressDisplay("")

        val sizeDetail = byteProgressPattern.find(raw)?.value?.let { value ->
            Regex("""(\d+)\.0(?=\s*(?:TB|GB|MB|KB|B)\b)""", RegexOption.IGNORE_CASE)
                .replace(value) { match -> match.groupValues[1] }
                .replace(Regex("""\s*/\s*"""), " / ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
        val withoutSize = byteProgressPattern.replace(raw, "")
        val withoutPercentage = percentagePattern.replace(withoutSize, "")
        val status = withoutPercentage
            .replace(Regex("""正在下载(?:…|\.\.\.)?"""), "下载中")
            .replace(Regex("""\s*·\s*"""), " · ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim(' ', '·', '•', '：', ':')
            .replace(Regex("""(?:·\s*)?已下载$"""), "")
            .trim()

        return SpeakerRecognitionProgressDisplay(
            message = status,
            secondaryDetail = sizeDetail,
        )
    }

    private fun formatCompactBytes(bytes: Long): String =
        io.translatetube.local.util.AudioDownloadProgressFormatter.formatBytes(bytes)
            .replace(Regex("""(\d+)\.0(?=\s)"""), "$1")

    /** Audio fetched for speaker diarization is secondary player work, not stage-one processing. */
    fun audioAcquisitionProgress(message: String): SpeakerRecognitionProgressUpdate {
        val percent = Regex("""(\d+(?:\.\d+)?)\s*%""")
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