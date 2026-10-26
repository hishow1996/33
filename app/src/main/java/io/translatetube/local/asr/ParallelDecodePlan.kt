package io.translatetube.local.asr

import kotlin.math.abs

/**
 * 长音频分段并行解码的纯逻辑部分（切段、对齐、拼缝校验），不依赖 Android，JVM 可测。
 *
 * 做法：把音频按时间切成几段，每段由独立线程用自己的 MediaExtractor/MediaCodec 解码，
 * 最后按顺序拼回一个 16 kHz 单声道 PCM 文件。要让拼出来的结果和顺序解码"一样"，有三点：
 *
 * 1. 段边界对齐到重采样网格：边界是 [alignmentStep] 的整数倍，这样每段重采样出来的第 n 个点
 *    正好落在全局网格上，拼接处不会错位。
 * 2. 每段多解码一小截（[SEAM_SAMPLES]）：既给重采样器补上右侧插值点，也留作拼缝校验。
 * 3. 拼缝校验：前一段多解的那一小截，必须和后一段开头解出来的样本一致（两个解码器是独立启动的，
 *    样本对得上才说明切点对齐、解码器预滚已收敛）。对不上就整体放弃，改走顺序解码。
 */
internal object ParallelDecodePlan {
    const val TARGET_RATE = 16_000
    /** 总时长不到这个值不值得并行（线程启动和拼接的开销会盖过收益）。 */
    const val MIN_TOTAL_DURATION_MS = 90_000L
    /** 每段至少这么长。 */
    const val MIN_SEGMENT_MS = 40_000L
    const val MAX_WORKERS = 4
    /** 每段结尾多解的样本数（源采样率），同时用于拼缝比对。 */
    const val SEAM_SAMPLES = 256
    /** 拼缝比对允许的单点误差（16 位 LSB）。 */
    const val SEAM_TOLERANCE = 32
    /** 低于该采样率的 AAC 多半是 HE-AAC（解码器实际输出翻倍），不走并行。 */
    const val MIN_SOURCE_RATE = 32_000
    const val MAX_SOURCE_RATE = 192_000
    /** 非首段从切点之前这么久开始喂数据，让解码器内部状态先收敛（AAC 只需一两帧，留足余量）。 */
    const val PREROLL_US = 500_000L
    /** 时间戳推算的帧位置与按数量累加的位置允许的差（采样点）。 */
    const val PTS_TOLERANCE_FRAMES = 4L

    /**
     * @param sourceEnd 不含；最后一段为 [Long.MAX_VALUE]（一直解到文件结束）
     * @param outputCount 本段应输出的 16 kHz 采样数；最后一段为 -1（有多少算多少）
     */
    data class Segment(
        val index: Int,
        val sourceStart: Long,
        val sourceEnd: Long,
        val outputStart: Long,
        val outputCount: Long,
    ) {
        val isFirst: Boolean get() = index == 0
        val isLast: Boolean get() = outputCount < 0L
    }

    /** 一个解码缓冲区里落在保留范围内的部分。 */
    data class Slice(val skipFrames: Int, val takeFrames: Int)

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    /** 源采样率下，每隔这么多采样点，输出网格正好是整数个 16 kHz 采样点。 */
    fun alignmentStep(sourceRate: Int): Long = (sourceRate / gcd(sourceRate, TARGET_RATE)).toLong()

    /** 与 [alignmentStep] 对应的 16 kHz 采样点数。 */
    fun outputPerStep(sourceRate: Int): Long = (TARGET_RATE / gcd(sourceRate, TARGET_RATE)).toLong()

    /** @param cores 可用于解码的核数（调用方已留出一个给界面） */
    fun workerCount(durationMs: Long, cores: Int): Int {
        if (durationMs < MIN_TOTAL_DURATION_MS) return 1
        val bySize = (durationMs / MIN_SEGMENT_MS).toInt()
        return minOf(MAX_WORKERS, cores, bySize).coerceAtLeast(1)
    }

    /** 切段；条件不满足（时长/采样率不合适、段太短）返回 null，调用方走顺序解码。 */
    fun plan(sourceRate: Int, durationMs: Long, workers: Int): List<Segment>? {
        if (workers < 2 || durationMs <= 0L) return null
        if (sourceRate !in MIN_SOURCE_RATE..MAX_SOURCE_RATE) return null
        val step = alignmentStep(sourceRate)
        val outStep = outputPerStep(sourceRate)
        val totalSource = durationMs * sourceRate / 1000L
        val boundaries = ArrayList<Long>(workers)
        boundaries += 0L
        for (k in 1 until workers) {
            val raw = totalSource * k / workers
            val aligned = raw - raw % step
            if (aligned <= boundaries.last()) return null
            boundaries += aligned
        }
        return List(workers) { i ->
            val start = boundaries[i]
            val outputStart = start / step * outStep
            if (i == workers - 1) {
                Segment(i, start, Long.MAX_VALUE, outputStart, -1L)
            } else {
                val end = boundaries[i + 1]
                Segment(i, start, end, outputStart, (end - start) / step * outStep)
            }
        }
    }

    /** 预计源采样总数（用于进度），按时长估算。 */
    fun estimatedSourceSamples(sourceRate: Int, durationMs: Long): Long =
        durationMs.coerceAtLeast(0L) * sourceRate / 1000L

    /** 时间戳（微秒）→ 相对起点的帧序号（四舍五入）。 */
    fun frameIndexAtUs(ptsUs: Long, originUs: Long, sampleRate: Int): Long =
        Math.round((ptsUs - originUs).toDouble() * sampleRate / 1_000_000.0)

    /** 帧序号 → 时间戳（微秒）。 */
    fun timeUsForFrame(frame: Long, sampleRate: Int, originUs: Long): Long =
        originUs + frame * 1_000_000L / sampleRate

    fun isContiguous(expectedFrame: Long, ptsFrame: Long, tolerance: Long = PTS_TOLERANCE_FRAMES): Boolean =
        abs(expectedFrame - ptsFrame) <= tolerance

    /** 一个缓冲区 [bufferStart, bufferStart+bufferFrames) 与保留范围 [keepFrom, keepTo) 的交集；没有交集返回 null。 */
    fun sliceOfBuffer(bufferStart: Long, bufferFrames: Int, keepFrom: Long, keepTo: Long): Slice? {
        if (bufferFrames <= 0) return null
        val from = maxOf(bufferStart, keepFrom)
        val to = minOf(bufferStart + bufferFrames, keepTo)
        if (to <= from) return null
        return Slice((from - bufferStart).toInt(), (to - from).toInt())
    }

    /** 前一段多解的结尾 [tail] 与后一段开头 [head] 是否一致。 */
    fun seamMatches(tail: ShortArray, head: ShortArray, tolerance: Int = SEAM_TOLERANCE): Boolean {
        if (tail.isEmpty() || tail.size != head.size) return false
        for (i in tail.indices) {
            if (abs(tail[i] - head[i]) > tolerance) return false
        }
        return true
    }

    /** 拼出的总长度是否与容器标称时长大致吻合（误差 3% + 1 秒）；对不上说明时长/切点不可信。 */
    fun totalLooksRight(totalOutputSamples: Long, durationMs: Long): Boolean {
        if (totalOutputSamples <= 0L || durationMs <= 0L) return false
        val expected = durationMs * TARGET_RATE / 1000L
        val allowed = expected * 3L / 100L + TARGET_RATE
        return abs(totalOutputSamples - expected) <= allowed
    }
}
