package io.translatetube.local.player

/** Bounded static-buffer sizes to retry when an OEM audio service rejects a larger track. */
internal object AudioTrackChunkPolicy {
    const val MIN_STATIC_TRACK_SAMPLES = 2_048

    /** Try the requested size first, then halve until the minimum size is reached. */
    fun candidateSampleCounts(
        requestedCount: Int,
        minimumCount: Int = MIN_STATIC_TRACK_SAMPLES,
    ): List<Int> {
        if (requestedCount <= 0) return emptyList()
        val minimum = minimumCount.coerceAtLeast(1).coerceAtMost(requestedCount)
        val candidates = ArrayList<Int>(8)
        var candidate = requestedCount
        while (true) {
            candidates += candidate
            if (candidate <= minimum) break
            val next = maxOf(minimum, candidate / 2)
            if (next >= candidate) break
            candidate = next
        }
        return candidates
    }

    fun failureMessage(sampleRate: Int, candidateCounts: List<Int>, detail: String?): String {
        val sizes = candidateCounts.joinToString(", ")
        return "系统配音音轨创建失败：已尝试 ${sizes.ifBlank { "无有效缓冲区" }} 个采样点的静态缓冲区" +
            "（${sampleRate}Hz）；最后错误：${detail?.take(240).orEmpty().ifBlank { "系统未提供详细原因" }}"
    }
}
