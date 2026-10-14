package io.translatetube.local.tts

import kotlin.math.abs

/**
 * 裁掉合成音频首尾的静音段，使配音起点贴近字幕起点。
 *
 * TTS（尤其 VITS 系）句首常带 150~300ms 停顿，直接播放会让配音
 * 相对字幕「晚起口」。这里只裁首尾低幅值样本，保留少量呼吸余量，
 * 避免把辅音起头或句尾气声裁伤。纯函数、无状态，便于 JVM 单测。
 */
internal object TtsEdgeSilenceTrimmer {

    /** 低于该线性幅值视为静音（约 -48 dBFS）。 */
    private const val SILENCE_THRESHOLD = 0.004f

    /** 首尾各自最多裁掉的毫秒数，防止把极短/异常输出裁没。 */
    private const val MAX_TRIM_MS = 400

    /** 裁剪后保留的余量毫秒数，让语句有自然起收。 */
    private const val KEEP_MARGIN_MS = 25

    fun trim(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples
        val maxTrim = MAX_TRIM_MS * sampleRate / 1000
        val margin = KEEP_MARGIN_MS * sampleRate / 1000

        var head = 0
        while (head < samples.size && head < maxTrim && abs(samples[head]) < SILENCE_THRESHOLD) head++

        // head 可能被 maxTrim 截停在静音区中部：先确认剩余部分是否全为静音，
        // 全静音输出不裁（保持原长，避免时间轴塌陷），由上层策略决定取舍。
        var hasLoud = false
        for (i in head until samples.size) {
            if (abs(samples[i]) >= SILENCE_THRESHOLD) { hasLoud = true; break }
        }
        if (!hasLoud) return samples

        var tail = samples.size - 1
        while (tail > head && (samples.size - 1 - tail) < maxTrim && abs(samples[tail]) < SILENCE_THRESHOLD) tail--

        val from = (head - margin).coerceAtLeast(0)
        val toExclusive = (tail + 1 + margin).coerceAtMost(samples.size)
        if (from <= 0 && toExclusive >= samples.size) return samples
        if (toExclusive <= from) return samples
        return samples.copyOfRange(from, toExclusive)
    }
}
