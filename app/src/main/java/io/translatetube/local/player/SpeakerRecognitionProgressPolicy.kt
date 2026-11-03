package io.translatetube.local.player

/**
 * Converts only measured speaker-recognition signals into a percentage.
 * An active chunk is not counted as completed until its inference has finished.
 */
object SpeakerRecognitionProgressPolicy {
    private val percentagePattern = Regex("""(\d{1,3})%$""")
    private val chunkPattern = Regex("""(正在分析|已分析)音频分段\s*(\d+)\s*/\s*(\d+)""")

    fun fromMessage(message: String): Int? {
        percentagePattern.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it.coerceIn(0, 100) }

        val match = chunkPattern.find(message) ?: return null
        val currentChunk = match.groupValues[2].toIntOrNull() ?: return null
        val totalChunks = match.groupValues[3].toIntOrNull() ?: return null
        if (totalChunks <= 0 || currentChunk !in 1..totalChunks) return null

        val completedChunks = if (match.groupValues[1] == "正在分析") {
            currentChunk - 1
        } else {
            currentChunk
        }
        return percentForCount(completedChunks, totalChunks)
    }

    fun percentForCount(completedCount: Int, totalCount: Int): Int? {
        if (totalCount <= 0) return null
        val completed = completedCount.coerceIn(0, totalCount).toLong()
        return (completed * 100L / totalCount).toInt().coerceIn(0, 100)
    }
}