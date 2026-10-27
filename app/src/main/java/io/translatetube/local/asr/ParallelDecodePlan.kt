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
 * 2. 每段多解码一小截（[SEAM_OVERLAP_MS]）：既给重采样器补上右侧插值点，也留作拼缝校验。
 * 3. 拼缝校验：前一段多解的 1.5 秒，必须和后一段开头解出来的样本逐点对齐（两个解码器是独立启动的）。
 *    按 ±64 个采样点的错位搜索，只有"不错位几乎一致、错位 1 个采样点就明显变差"才通过，
 *    量不准（静音等）一律不通过。对不上就整体放弃，改走顺序解码。
 */
internal object ParallelDecodePlan {
    const val TARGET_RATE = 16_000
    /** 总时长不到这个值不值得并行（线程启动和拼接的开销会盖过收益）。 */
    const val MIN_TOTAL_DURATION_MS = 90_000L
    /** 每段至少这么长。 */
    const val MIN_SEGMENT_MS = 40_000L
    const val MAX_WORKERS = 4
    /** 每段结尾多解这么久（毫秒），后一段从切点开头解出同一段，两边逐点比对。 */
    const val SEAM_OVERLAP_MS = 1_500L
    /** 拼缝比对时搜索的最大错位（采样点，正负各这么多）。 */
    const val SEAM_MAX_LAG = 64
    /** 认为"对齐"要求的平均绝对差（16 位 LSB）。同一个解码器解同一段音频应当几乎逐点一致。 */
    const val SEAM_ALIGNED_MEAN_DIFF = 2.0
    /** 错位 1 个采样点以上时的平均差必须至少是对齐时的这么多倍（且不小于 [SEAM_MIN_OTHER_DIFF]），才算"量得准"。 */
    const val SEAM_MIN_CONTRAST = 4.0
    const val SEAM_MIN_OTHER_DIFF = 4.0
    /** 低于该采样率的 AAC 多半是 HE-AAC（解码器实际输出翻倍），不走并行。 */
    const val MIN_SOURCE_RATE = 32_000
    const val MAX_SOURCE_RATE = 192_000
    /** 非首段从切点之前这么久开始喂数据，让解码器内部状态先收敛（AAC 只需一两帧，留足余量）。 */
    const val PREROLL_US = 500_000L
    /** 时间戳推算的帧位置与按数量累加的位置允许的差（采样点）。 */
    const val PTS_TOLERANCE_FRAMES = 4L
    /** 开头被解码器丢掉的样本数上限（Opus pre-skip 约 6.5ms，留足余量）。 */
    const val MAX_LEADING_DISCARD_MS = 500L

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

    /** 时间戳精度只有毫秒的容器（WebM/Matroska）会有最多半毫秒的舍入，容差随采样率放宽。 */
    fun ptsTolerance(sampleRate: Int): Long = maxOf(PTS_TOLERANCE_FRAMES, sampleRate / 1000L)

    /**
     * 解码器在流开头可能会丢掉一小段（Opus 的 pre-skip 等），于是"顺序解码的第 n 个样本"
     * 不等于"时间戳上的第 n 个样本"。用开头两个非空输出缓冲区实测这个差：
     * 第二个缓冲区按时间戳应该从 [secondBufferPtsFrame] 开始，而顺序解码里它紧接在第一个缓冲区
     * （[firstBufferFrames] 个样本）之后，差值就是被丢掉的样本数。
     * 差在时间戳误差内算 0；为负或大得离谱返回 null（行为异常，放弃并行）。
     */
    fun leadingDiscardFrames(secondBufferPtsFrame: Long, firstBufferFrames: Long, sampleRate: Int): Long? {
        val difference = secondBufferPtsFrame - firstBufferFrames
        val tolerance = ptsTolerance(sampleRate)
        val maxDiscard = sampleRate.toLong() * MAX_LEADING_DISCARD_MS / 1000L
        return when {
            difference in -tolerance..tolerance -> 0L
            difference in 1L..maxDiscard -> difference
            else -> null
        }
    }

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

    /** 拼缝重叠区的样本数（源采样率）。 */
    fun seamSamples(sampleRate: Int): Int = (sampleRate.toLong() * SEAM_OVERLAP_MS / 1000L).toInt()

    enum class SeamVerdict {
        /** 逐点一致，且错位哪怕 1 个采样点都会明显变差：确认对齐。 */
        ALIGNED,
        /** 在某个错位下才吻合：切点对错了位。 */
        MISALIGNED,
        /** 怎么错位都对不上：两边解出来的不是同一段音频。 */
        MISMATCH,
        /** 看上去一致，但音频太安静或太重复，分辨不出错位：无法证明对齐。 */
        INCONCLUSIVE,
    }

    class SeamCheck(
        val verdict: SeamVerdict,
        /** [SeamVerdict.MISALIGNED] 时为吻合的错位（采样点）。 */
        val lag: Int,
        /** 不错位时的平均绝对差。 */
        val meanDiffAtZero: Double,
        /** 错位 1~[SEAM_MAX_LAG] 个采样点中最小的平均绝对差。 */
        val meanDiffBestOther: Double,
    ) {
        fun describe(): String = when (verdict) {
            SeamVerdict.ALIGNED -> "对齐(平均差 ${round2(meanDiffAtZero)})"
            SeamVerdict.MISALIGNED -> "错位 $lag 个采样点"
            SeamVerdict.MISMATCH -> "两边音频对不上"
            SeamVerdict.INCONCLUSIVE -> "无法判断(音频太安静或太重复)"
        }

        private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
    }

    /**
     * 严格的拼缝对齐校验：前一段多解的结尾 [tail]（样本 [切点, 切点+重叠)）和后一段开头 [head]
     * 是同一段音频、由两个独立启动的解码器解出。
     *
     * 只有当"不错位时几乎逐点一致"并且"错位任意 1~[SEAM_MAX_LAG] 个采样点都明显更差"时才判定对齐。
     * 因此只要这段音频不是几乎静音，哪怕只差 1 个采样点（0.02ms）也会被发现；
     * 静音/纯重复音等分辨不出错位的情况返回 [SeamVerdict.INCONCLUSIVE]，调用方一律当作不通过。
     */
    fun compareSeam(tail: ShortArray, head: ShortArray): SeamCheck {
        val maxLag = SEAM_MAX_LAG
        val size = tail.size
        if (size != head.size || size < 8 * maxLag) {
            return SeamCheck(SeamVerdict.INCONCLUSIVE, 0, Double.NaN, Double.NaN)
        }
        val usable = size - 2 * maxLag

        fun meanDiff(lag: Int): Double {
            var sum = 0L
            for (i in maxLag until maxLag + usable) sum += abs(tail[i] - head[i + lag])
            return sum.toDouble() / usable
        }

        val zero = meanDiff(0)
        var bestLag = 0
        var best = Double.MAX_VALUE
        for (lag in -maxLag..maxLag) {
            if (lag == 0) continue
            val value = meanDiff(lag)
            if (value < best) {
                best = value
                bestLag = lag
            }
        }
        return when {
            zero <= SEAM_ALIGNED_MEAN_DIFF &&
                best >= maxOf(SEAM_MIN_CONTRAST * zero, SEAM_MIN_OTHER_DIFF) ->
                SeamCheck(SeamVerdict.ALIGNED, 0, zero, best)
            zero <= SEAM_ALIGNED_MEAN_DIFF -> SeamCheck(SeamVerdict.INCONCLUSIVE, 0, zero, best)
            best <= SEAM_ALIGNED_MEAN_DIFF -> SeamCheck(SeamVerdict.MISALIGNED, bestLag, zero, best)
            else -> SeamCheck(SeamVerdict.MISMATCH, 0, zero, best)
        }
    }

    /** 拼出的总长度是否与容器标称时长大致吻合（误差 3% + 1 秒）；对不上说明时长/切点不可信。 */
    fun totalLooksRight(totalOutputSamples: Long, durationMs: Long): Boolean {
        if (totalOutputSamples <= 0L || durationMs <= 0L) return false
        val expected = durationMs * TARGET_RATE / 1000L
        val allowed = expected * 3L / 100L + TARGET_RATE
        return abs(totalOutputSamples - expected) <= allowed
    }
}
