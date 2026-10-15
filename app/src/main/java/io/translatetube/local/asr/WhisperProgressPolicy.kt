package io.translatetube.local.asr

/** Rounds positive sample durations up so short clips never report 0/0 progress. */
internal object WhisperProgressPolicy {
    fun secondsCeiling(sampleCount: Int, sampleRate: Int): Long {
        require(sampleCount >= 0) { "样本数不能为负数" }
        require(sampleRate > 0) { "采样率必须为正数" }
        if (sampleCount == 0) return 0L
        return (sampleCount.toLong() + sampleRate.toLong() - 1L) / sampleRate.toLong()
    }
}
