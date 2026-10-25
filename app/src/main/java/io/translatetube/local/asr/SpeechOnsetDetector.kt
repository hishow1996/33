package io.translatetube.local.asr

import io.translatetube.local.player.SubtitleTimingOffset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从原声音频里量出"第一句话真正开口"的时刻。
 *
 * 字幕起点来自 VAD / Whisper，偏晚多少因视频而异；配音是按字幕起点开口的，
 * 起点偏晚配音就比视频原声慢。这里直接看音频能量：在 VAD 起点附近找到第一段持续上升的声音，
 * 作为实测开口时间。只在把握很大时才给结果，否则返回 null，调用方保持原来的起点。
 *
 * 纯逻辑（输入是已读出的 16 kHz 样本），JVM 可测。
 */
internal object SpeechOnsetDetector {
    private const val FRAME_MS = 10L
    /** 往 VAD 起点之前最多找多远（VAD 起点偏晚的典型范围）。 */
    const val SEARCH_BEFORE_MS = 800L
    /** 往 VAD 起点之后最多找多远（VAD 被呼吸/杂音提前触发的情况）。 */
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

    /** 需要从 PCM 读出的范围（毫秒）。 */
    fun readRangeMs(vadStartMs: Long, durationMs: Long): Pair<Long, Long> {
        val from = max(0L, vadStartMs - CONTEXT_BEFORE_MS)
        val to = min(durationMs, vadStartMs + SEARCH_AFTER_MS + SUSTAIN_FRAMES * FRAME_MS)
        return from to max(from, to)
    }

    /**
     * @param samples 从 [sliceStartMs] 开始的连续样本
     * @return 实测开口时间（毫秒，媒体时间轴）；没有把握返回 null
     */
    fun findOnsetMs(
        samples: FloatArray,
        sampleRate: Int,
        sliceStartMs: Long,
        vadStartMs: Long,
    ): Long? {
        if (sampleRate <= 0 || sliceStartMs < 0L || vadStartMs < 0L) return null
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

        val searchFromMs = max(sliceStartMs, vadStartMs - SEARCH_BEFORE_MS)
        val searchToMs = vadStartMs + SEARCH_AFTER_MS
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

    internal data class Refinement(
        val lines: List<Triple<Long, Long, String>>,
        /** 实测的开口时间；没有量出来时为 null。 */
        val measuredOnsetMs: Long?,
        val previousStartMs: Long,
        val newStartMs: Long,
    )

    /**
     * 用实测开口时间校正第一句的起点。
     *
     * 字幕/配音统一会在起点之前提前 [SubtitleTimingOffset.LEAD_MS]，第一句还要再提前
     * [SubtitleTimingOffset.FIRST_CUE_EXTRA_LEAD_MS]；所以这里把起点写成
     * "实测开口 + 这两个提前量"，字幕亮起和配音开口就正好落在实测开口那一刻。
     */
    fun refineFirstCue(
        lines: List<Triple<Long, Long, String>>,
        pcm: AudioExtract.PcmFile,
        checkActive: () -> Unit = {},
    ): Refinement? {
        if (lines.isEmpty() || pcm.sampleRate <= 0 || pcm.sampleCount <= 0L) return null
        val order = lines.indices.sortedBy { lines[it].first }
        val firstIndex = order.first()
        val first = lines[firstIndex]
        val second = order.getOrNull(1)?.let { lines[it] }

        val (fromMs, toMs) = readRangeMs(first.first, pcm.durationMs)
        val sampleRate = pcm.sampleRate
        val startSample = fromMs * sampleRate / 1000L
        val endSample = (toMs * sampleRate / 1000L).coerceAtMost(pcm.sampleCount)
        val count = endSample - startSample
        if (count <= 0L || count > Int.MAX_VALUE) return null
        checkActive()
        val samples = pcm.readFloatSamples(startSample, count.toInt(), checkActive)
        val onset = findOnsetMs(samples, sampleRate, fromMs, first.first) ?: return null

        val newStart = onset + SubtitleTimingOffset.LEAD_MS + SubtitleTimingOffset.FIRST_CUE_EXTRA_LEAD_MS
        // 不能把第一句挪到它自己的结尾之后，也不能越过第二句。
        val limit = min(first.second, second?.first ?: Long.MAX_VALUE) - MIN_CUE_MS
        if (newStart >= limit || newStart < 0L) return null
        if (newStart == first.first) return Refinement(lines, onset, first.first, newStart)

        val adjusted = lines.toMutableList()
        adjusted[firstIndex] = Triple(newStart, first.second, first.third)
        return Refinement(adjusted, onset, first.first, newStart)
    }

    private const val MIN_CUE_MS = 100L
}
