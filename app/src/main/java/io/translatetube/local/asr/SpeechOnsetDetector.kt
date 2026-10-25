package io.translatetube.local.asr

import io.translatetube.local.player.SubtitleTimingOffset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从原声音频里量出"第一句话真正开口"的时刻。
 *
 * YouTube 自带字幕的起点是字幕制作/自动生成时的时间，不是人声真正开口的时间，
 * 第一句配音（按字幕起点开口）因此比视频原声慢一点。这里直接看音频能量：
 * 在字幕起点附近找到第一段持续上升的声音，作为实测开口时间。
 * 只在把握很大时才给结果，否则返回 null，调用方保持原来的起点。
 *
 * 纯逻辑（输入是已读出的 16 kHz 样本），JVM 可测。
 */
internal object SpeechOnsetDetector {
    private const val FRAME_MS = 10L
    /** 往字幕起点之前最多找多远。 */
    const val SEARCH_BEFORE_MS = 800L
    /** 往字幕起点之后最多找多远（字幕比声音早的情况）。 */
    const val SEARCH_AFTER_MS = 400L
    /** 估计底噪用的上文长度。 */
    const val CONTEXT_BEFORE_MS = 1_500L
    private const val SUSTAIN_FRAMES = 10
    private const val SUSTAIN_MIN_ACTIVE = 7
    private const val MIN_THRESHOLD = 0.008f
    private const val NOISE_MULTIPLE = 4f
    private const val NOISE_PERCENTILE = 0.2
    /** 搜索起点之前这么多帧如果已经很响，说明前面有持续的音乐/说话，无法判断开口点。 */
    private const val ALREADY_LOUD_FRAMES = 5
    /** 起点变化小于这个值就不动，避免无意义的改动。 */
    private const val MIN_CHANGE_MS = 40L
    /** 校正后的第一句至少要留这么长。 */
    private const val MIN_CUE_MS = 100L

    /** 需要从 PCM 读出的范围（毫秒）。 */
    fun readRangeMs(captionStartMs: Long, durationMs: Long): Pair<Long, Long> {
        val from = max(0L, captionStartMs - CONTEXT_BEFORE_MS)
        val to = min(durationMs, captionStartMs + SEARCH_AFTER_MS + SUSTAIN_FRAMES * FRAME_MS)
        return from to max(from, to)
    }

    /**
     * @param samples 从 [sliceStartMs] 开始的连续样本
     * @param captionStartMs 字幕给出的第一句起点
     * @return 实测开口时间（毫秒，媒体时间轴）；没有把握返回 null
     */
    fun findOnsetMs(
        samples: FloatArray,
        sampleRate: Int,
        sliceStartMs: Long,
        captionStartMs: Long,
    ): Long? {
        if (sampleRate <= 0 || sliceStartMs < 0L || captionStartMs < 0L) return null
        val frameLen = (sampleRate * FRAME_MS / 1000L).toInt()
        if (frameLen <= 0) return null
        val frameCount = samples.size / frameLen
        if (frameCount < SUSTAIN_FRAMES + ALREADY_LOUD_FRAMES) return null

        val rms = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0.0
            val base = frame * frameLen
            for (k in 0 until frameLen) {
                val s = samples[base + k].toDouble()
                if (!s.isFinite()) return null
                sum += s * s
            }
            rms[frame] = sqrt(sum / frameLen).toFloat()
        }

        val sorted = rms.copyOf().also { it.sort() }
        val noiseFloor = sorted[(frameCount * NOISE_PERCENTILE).toInt().coerceIn(0, frameCount - 1)]
        val threshold = max(MIN_THRESHOLD, noiseFloor * NOISE_MULTIPLE)

        val searchFromMs = max(sliceStartMs, captionStartMs - SEARCH_BEFORE_MS)
        val searchToMs = captionStartMs + SEARCH_AFTER_MS
        val fromFrame = ((searchFromMs - sliceStartMs) / FRAME_MS).toInt().coerceAtLeast(0)
        val toFrame = min(frameCount - SUSTAIN_FRAMES, ((searchToMs - sliceStartMs) / FRAME_MS).toInt())
        if (toFrame < fromFrame) return null

        // 搜索起点之前已经有持续的响声（片头音乐、上一句的尾巴）：量不出这一句从哪开始。
        if (sliceStartMs > 0L && fromFrame >= ALREADY_LOUD_FRAMES) {
            var before = 0f
            for (i in fromFrame - ALREADY_LOUD_FRAMES until fromFrame) before += rms[i]
            if (before / ALREADY_LOUD_FRAMES >= threshold) return null
        }

        for (i in fromFrame..toFrame) {
            if (rms[i] < threshold) continue
            var active = 0
            for (k in i until i + SUSTAIN_FRAMES) {
                if (rms[k] >= threshold * 0.7f) active++
            }
            if (active >= SUSTAIN_MIN_ACTIVE) {
                return max(0L, sliceStartMs + i * FRAME_MS - FRAME_MS)
            }
        }
        return null
    }

    /**
     * 把实测开口时间换算成第一句应有的字幕起点。
     *
     * 字幕/配音统一在起点之前提前 [SubtitleTimingOffset.LEAD_MS] 开口，所以起点写成
     * "实测开口 + 统一提前量"，配音就正好在人开口那一刻开始。
     * 只把起点往前挪（字幕比实测晚才修）；变化太小、或会越过本句结尾/下一句时返回 null，保持原样。
     */
    fun calibratedStartMs(
        onsetMs: Long,
        currentStartMs: Long,
        endMs: Long,
        nextStartMs: Long?,
    ): Long? {
        val newStart = onsetMs + SubtitleTimingOffset.LEAD_MS
        if (newStart < 0L) return null
        if (newStart > currentStartMs - MIN_CHANGE_MS) return null
        val limit = min(endMs, nextStartMs ?: Long.MAX_VALUE) - MIN_CUE_MS
        if (newStart >= limit) return null
        return newStart
    }
}
