package io.translatetube.local.player

/**
 * 配音 PCM 总线。各句按媒体时间预挂载，由 Media3 音频处理器按输入缓冲区的真实时间戳读取。
 *
 * 块在后台生产线程重采样、排序和去重；渲染线程只遍历有交集的块并累加样本，
 * 不在读侧排序，也不为每个块分配中间缓冲区。
 *
 * 两种挂载方式：
 * - [feed]：块固定在给定的媒体时间，重叠的块直接相加（原有契约，保持不变）；
 * - [feedSequenced]：配音句子专用。句子按期望时间依次开口，上一句没说完就排在它后面，
 *   绝不叠音；准备晚了的句子从渲染头之后整句播出，不丢开头。排版规则见 [DubbingCueLayout]。
 */
class DubbingTrackBus {

    private class Block(
        val cueKey: String?,
        /** 期望的媒体时间（微秒）。排队块的实际开口时间可能晚于它。 */
        val mediaTimeUs: Long,
        /** 实际开口帧位置。 */
        val startFrame: Long,
        val samples: FloatArray,
        /** 排队块参与排版；普通块固定在 [startFrame]。 */
        val sequenced: Boolean = false,
        /** 排队块自己的开口下限（挂载时的播放头）。 */
        val notBeforeFrame: Long = Long.MIN_VALUE,
    ) {
        val endFrame: Long = startFrame + samples.size.toLong()

        fun withStart(frame: Long): Block =
            if (frame == startFrame) this
            else Block(cueKey, mediaTimeUs, frame, samples, sequenced, notBeforeFrame)
    }

    private val stateLock = Any()
    @Volatile private var installed: Array<Block> = emptyArray()
    @Volatile private var sinkSampleRate = 24_000
    @Volatile private var dubbingEnabled = true
    @Volatile private var contentGeneration = 0L
    /** Fallback clock only; normal synchronization uses each AudioSink buffer PTS. */
    @Volatile private var streamStartUs = -1L
    @Volatile private var latestReadUs = Long.MIN_VALUE

    fun configure(sampleRate: Int) {
        if (sampleRate <= 0) return
        synchronized(stateLock) {
            if (sinkSampleRate == sampleRate) return
            sinkSampleRate = sampleRate
            contentGeneration += 1L
            installed = emptyArray()
            latestReadUs = Long.MIN_VALUE
        }
    }

    fun setStreamStartUs(mediaUs: Long) {
        streamStartUs = mediaUs
    }

    fun streamStart(): Long = streamStartUs
    fun generationToken(): Long = contentGeneration

    fun feed(
        mediaTimeUs: Long,
        samples: FloatArray,
        sourceSampleRate: Int,
    ): Boolean = feedInternal(
        cueKey = null,
        mediaTimeUs = mediaTimeUs,
        samples = samples,
        sourceSampleRate = sourceSampleRate,
        expectedGeneration = null,
        isStillCurrent = null,
        sequenced = false,
        notBeforeUs = Long.MIN_VALUE,
    ) >= 0L

    /** Refeeding the same cue replaces its prior block instead of doubling its loudness. */
    fun feed(
        cueKey: String,
        mediaTimeUs: Long,
        samples: FloatArray,
        sourceSampleRate: Int,
        expectedGeneration: Long? = null,
        isStillCurrent: (() -> Boolean)? = null,
    ): Boolean = feedInternal(
        cueKey = cueKey,
        mediaTimeUs = mediaTimeUs,
        samples = samples,
        sourceSampleRate = sourceSampleRate,
        expectedGeneration = expectedGeneration,
        isStillCurrent = isStillCurrent,
        sequenced = false,
        notBeforeUs = Long.MIN_VALUE,
    ) >= 0L

    /**
     * 配音句子专用挂载：按 [desiredMediaTimeUs] 排队开口，不与其它排队句叠音。
     * [notBeforeUs] 是调用方已知的当前播放头（真实媒体时间），句子不会早于它开口。
     * 已经开始播放的同一句不会被替换或移动。
     *
     * @return 实际开口的媒体时间（微秒）；被拒绝（过期、已关闭配音等）返回 -1。
     */
    fun feedSequenced(
        cueKey: String,
        desiredMediaTimeUs: Long,
        notBeforeUs: Long,
        samples: FloatArray,
        sourceSampleRate: Int,
        expectedGeneration: Long? = null,
        isStillCurrent: (() -> Boolean)? = null,
    ): Long = feedInternal(
        cueKey = cueKey,
        mediaTimeUs = desiredMediaTimeUs,
        samples = samples,
        sourceSampleRate = sourceSampleRate,
        expectedGeneration = expectedGeneration,
        isStillCurrent = isStillCurrent,
        sequenced = true,
        notBeforeUs = notBeforeUs,
    )

    private fun feedInternal(
        cueKey: String?,
        mediaTimeUs: Long,
        samples: FloatArray,
        sourceSampleRate: Int,
        expectedGeneration: Long?,
        isStillCurrent: (() -> Boolean)?,
        sequenced: Boolean,
        notBeforeUs: Long,
    ): Long {
        if (samples.isEmpty() || sourceSampleRate <= 0 || mediaTimeUs < 0L) return -1L
        val snapshot = synchronized(stateLock) {
            if (!dubbingEnabled) return -1L
            if (expectedGeneration != null && expectedGeneration != contentGeneration) return -1L
            Triple(contentGeneration, sinkSampleRate, latestReadUs)
        }
        if (isStillCurrent?.let { runCatching(it).getOrDefault(false) } == false) return -1L

        val targetRate = snapshot.second
        val resampled = if (targetRate == sourceSampleRate) {
            samples
        } else {
            val outputCount = (
                (samples.size.toLong() * targetRate + sourceSampleRate / 2L) / sourceSampleRate
            ).toInt().coerceAtLeast(1)
            val ratio = sourceSampleRate.toDouble() / targetRate.toDouble()
            FloatArray(outputCount) { index ->
                val sourcePosition = index * ratio
                val low = sourcePosition.toInt().coerceIn(0, samples.lastIndex)
                val high = (low + 1).coerceAtMost(samples.lastIndex)
                val fraction = (sourcePosition - low).toFloat()
                samples[low] * (1f - fraction) + samples[high] * fraction
            }
        }

        if (isStillCurrent?.let { runCatching(it).getOrDefault(false) } == false) return -1L
        synchronized(stateLock) {
            if (!dubbingEnabled || contentGeneration != snapshot.first) return -1L
            if (expectedGeneration != null && contentGeneration != expectedGeneration) return -1L
            if (isStillCurrent?.let { runCatching(it).getOrDefault(false) } == false) return -1L

            val readHead = latestReadUs
            val readHeadFrame = if (readHead == Long.MIN_VALUE) Long.MIN_VALUE
                else frameAtUs(readHead, targetRate)
            val cutoffUs = if (readHead == Long.MIN_VALUE) Long.MIN_VALUE
                else (readHead - RETAIN_PAST_MS * 1_000L).coerceAtLeast(0L)
            val cutoffFrame = if (cutoffUs == Long.MIN_VALUE) Long.MIN_VALUE
                else frameAtUs(cutoffUs, targetRate)

            // 同一句已经开始播放：不替换、不移动，避免半句重播或断开。
            if (sequenced && cueKey != null && readHeadFrame != Long.MIN_VALUE) {
                val playing = installed.firstOrNull {
                    it.sequenced && it.cueKey == cueKey && it.startFrame < readHeadFrame
                }
                if (playing != null) return usAtFrame(playing.startFrame, targetRate)
            }

            val next = ArrayList<Block>(installed.size + 1)
            for (block in installed) {
                val sameCue = cueKey != null && block.cueKey == cueKey
                val stillRelevant = cutoffFrame == Long.MIN_VALUE || block.endFrame > cutoffFrame
                if (!sameCue && stillRelevant) next.add(block)
            }

            val desiredFrame = frameAtUs(mediaTimeUs, targetRate)
            val notBeforeFrame = if (notBeforeUs == Long.MIN_VALUE) Long.MIN_VALUE
                else frameAtUs(notBeforeUs.coerceAtLeast(0L), targetRate)
            val placedStart: Long
            if (sequenced) {
                val laidOut = layoutSequenced(
                    existing = next,
                    candidate = Candidate(desiredFrame, resampled.size.toLong(), notBeforeFrame),
                    rate = targetRate,
                    readHeadFrame = readHeadFrame,
                )
                for (i in next.indices) next[i] = next[i].withStart(laidOut.existingStarts[i])
                placedStart = laidOut.candidateStart
            } else {
                placedStart = desiredFrame
            }
            val endFrame = placedStart + resampled.size.toLong()
            if (cutoffFrame != Long.MIN_VALUE && endFrame <= cutoffFrame) {
                installed = next.toTypedArray()
                return -1L
            }
            next.add(Block(cueKey, mediaTimeUs, placedStart, resampled, sequenced, notBeforeFrame))
            next.sortWith(compareBy<Block> { it.startFrame }.thenBy { it.mediaTimeUs })
            installed = next.toTypedArray()
            return usAtFrame(placedStart, targetRate)
        }
    }

    /**
     * 预演：如果现在挂载这一句，它会在什么时候开口（媒体时间，微秒）？不改变任何状态。
     * 调用方据此判断这句是否被前面的句子顶后太多，需要追赶压缩。
     */
    fun projectStartUs(
        cueKey: String,
        desiredMediaTimeUs: Long,
        notBeforeUs: Long,
        durationUs: Long,
    ): Long {
        if (desiredMediaTimeUs < 0L || durationUs <= 0L) return desiredMediaTimeUs
        synchronized(stateLock) {
            if (!dubbingEnabled) return desiredMediaTimeUs
            val rate = sinkSampleRate
            val readHead = latestReadUs
            val readHeadFrame = if (readHead == Long.MIN_VALUE) Long.MIN_VALUE
                else frameAtUs(readHead, rate)
            val playing = readHeadFrame != Long.MIN_VALUE && installed.any {
                it.sequenced && it.cueKey == cueKey && it.startFrame < readHeadFrame
            }
            if (playing) {
                val block = installed.first { it.sequenced && it.cueKey == cueKey }
                return usAtFrame(block.startFrame, rate)
            }
            val others = installed.filter { !(it.cueKey != null && it.cueKey == cueKey) }
            val laidOut = layoutSequenced(
                existing = others,
                candidate = Candidate(
                    desiredFrame = frameAtUs(desiredMediaTimeUs, rate),
                    lengthFrames = (durationUs * rate / 1_000_000L).coerceAtLeast(1L),
                    notBeforeFrame = if (notBeforeUs == Long.MIN_VALUE) Long.MIN_VALUE
                        else frameAtUs(notBeforeUs.coerceAtLeast(0L), rate),
                ),
                rate = rate,
                readHeadFrame = readHeadFrame,
            )
            return usAtFrame(laidOut.candidateStart, rate)
        }
    }

    /** 这一句（按 cueKey）是否已经挂在总线上。 */
    fun hasCue(cueKey: String): Boolean = installed.any { it.cueKey == cueKey }

    /**
     * 移除某个前缀下的所有句子。引擎被替换/重新合成时调用，防止旧引擎的音频留在共享总线上
     * 到点后与新配音叠在一起。
     */
    fun removeCuesWithPrefix(prefix: String) {
        if (prefix.isEmpty()) return
        synchronized(stateLock) {
            val kept = installed.filter { it.cueKey?.startsWith(prefix) != true }
            if (kept.size != installed.size) installed = kept.toTypedArray()
        }
    }

    private class Candidate(
        val desiredFrame: Long,
        val lengthFrames: Long,
        val notBeforeFrame: Long,
    )

    private class LaidOut(val existingStarts: LongArray, val candidateStart: Long)

    /**
     * 对所有排队块加上候选块一起重新排版。普通块（[feed]）不参与，保持原位。
     * 已经开始播放（开口早于渲染头）的块位置固定。
     */
    private fun layoutSequenced(
        existing: List<Block>,
        candidate: Candidate,
        rate: Int,
        readHeadFrame: Long,
    ): LaidOut {
        val indices = ArrayList<Int>(existing.size)
        val items = ArrayList<DubbingCueLayout.Item>(existing.size + 1)
        for (i in existing.indices) {
            val block = existing[i]
            if (!block.sequenced) continue
            indices.add(i)
            val started = readHeadFrame != Long.MIN_VALUE && block.startFrame < readHeadFrame
            items.add(
                DubbingCueLayout.Item(
                    desiredFrame = frameAtUs(block.mediaTimeUs, rate),
                    lengthFrames = block.samples.size.toLong(),
                    notBeforeFrame = block.notBeforeFrame,
                    frozenStartFrame = if (started) block.startFrame else null,
                )
            )
        }
        items.add(
            DubbingCueLayout.Item(
                desiredFrame = candidate.desiredFrame,
                lengthFrames = candidate.lengthFrames,
                notBeforeFrame = candidate.notBeforeFrame,
            )
        )
        val floor = if (readHeadFrame == Long.MIN_VALUE) Long.MIN_VALUE
            else readHeadFrame + rate.toLong() * MIN_LEAD_MS / 1_000L
        val gap = rate.toLong() * SENTENCE_GAP_MS / 1_000L
        val starts = DubbingCueLayout.layout(items, gap, floor)
        val existingStarts = LongArray(existing.size) { existing[it].startFrame }
        for (k in indices.indices) existingStarts[indices[k]] = starts[k]
        return LaidOut(existingStarts, starts[items.size - 1])
    }

    /** Turning dubbing off clears queued voice immediately and invalidates all in-flight feeds. */
    fun setDubbingEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            if (dubbingEnabled == enabled) return
            dubbingEnabled = enabled
            contentGeneration += 1L
            installed = emptyArray()
        }
    }

    /** Seek / source replacement: invalidate old blocks and asynchronous publishers. */
    fun reset() {
        synchronized(stateLock) {
            contentGeneration += 1L
            installed = emptyArray()
            streamStartUs = -1L
            latestReadUs = Long.MIN_VALUE
        }
    }

    /**
     * Check whether a queued cue overlaps a media-time window without advancing the render head.
     * The audio processor uses this to reject clearly misaligned sink timestamps and fall back to
     * the calibrated stream clock. This query must have no side effects on [latestReadUs].
     */
    fun hasAudioInWindow(mediaTimeUs: Long, count: Int): Boolean {
        if (count <= 0) return false
        val snapshot = synchronized(stateLock) {
            if (!dubbingEnabled) return false
            installed to sinkSampleRate
        }
        val startFrame = frameAtUs(mediaTimeUs, snapshot.second)
        val endFrame = startFrame + count.toLong()
        return snapshot.first.any { it.startFrame < endFrame && it.endFrame > startFrame }
    }

    /** Allocating helper for diagnostics/tests; the render path should call [mixInto]. */
    fun readMixWindow(mediaTimeUs: Long, count: Int): FloatArray =
        FloatArray(count.coerceAtLeast(0)).also { mixInto(mediaTimeUs, it.size, it) }

    /** Add every block intersecting the requested window into a caller-owned reusable buffer. */
    fun mixInto(mediaTimeUs: Long, count: Int, out: FloatArray) {
        if (count <= 0 || out.size < count) return
        java.util.Arrays.fill(out, 0, count, 0f)
        val snapshot = synchronized(stateLock) {
            if (!dubbingEnabled) return
            Triple(installed, sinkSampleRate, contentGeneration)
        }
        val blocks = snapshot.first
        val rate = snapshot.second
        val readGeneration = snapshot.third
        val windowStartFrame = frameAtUs(mediaTimeUs, rate)
        val windowEndFrame = windowStartFrame + count.toLong()
        val fadeInFrames = rate * FADE_IN_MS / 1_000
        val fadeOutFrames = rate * FADE_OUT_MS / 1_000

        for (block in blocks) {
            if (block.startFrame >= windowEndFrame) break
            if (block.endFrame <= windowStartFrame) continue
            val destinationStart = (block.startFrame - windowStartFrame).coerceAtLeast(0L).toInt()
            val sourceStart = (windowStartFrame - block.startFrame).coerceAtLeast(0L).toInt()
            val copyCount = minOf(count - destinationStart, block.samples.size - sourceStart)
            if (copyCount <= 0) continue
            // 块首尾各做一小段淡入淡出，避免边界处的爆音；中间部分原样相加。
            val length = block.samples.size
            val fadeIn = minOf(fadeInFrames, length / 2)
            val fadeOut = minOf(fadeOutFrames, length / 2)
            val steadyEnd = length - fadeOut
            var i = 0
            while (i < copyCount) {
                val position = sourceStart + i
                val sample = block.samples[position]
                out[destinationStart + i] += if (position in fadeIn until steadyEnd) {
                    sample
                } else {
                    var gain = 1f
                    if (position < fadeIn) gain = position.toFloat() / fadeIn
                    val fromEnd = length - position
                    if (fromEnd <= fadeOut) gain = minOf(gain, fromEnd.toFloat() / fadeOut)
                    sample * gain
                }
                i++
            }
        }

        val windowEndUs = mediaTimeUs + count.toLong() * 1_000_000L / rate
        synchronized(stateLock) {
            if (dubbingEnabled && contentGeneration == readGeneration) {
                latestReadUs = maxOf(latestReadUs, windowEndUs)
            }
        }
        if (!dubbingEnabled || contentGeneration != readGeneration) {
            java.util.Arrays.fill(out, 0, count, 0f)
        }
    }

    private fun frameAtUs(timeUs: Long, sampleRate: Int): Long {
        val safeUs = timeUs.coerceAtLeast(0L)
        return (safeUs / 1_000_000L) * sampleRate.toLong() +
            (safeUs % 1_000_000L) * sampleRate.toLong() / 1_000_000L
    }

    private fun usAtFrame(frame: Long, sampleRate: Int): Long {
        val safeFrame = frame.coerceAtLeast(0L)
        return (safeFrame / sampleRate) * 1_000_000L +
            (safeFrame % sampleRate) * 1_000_000L / sampleRate
    }

    private companion object {
        const val RETAIN_PAST_MS = 5_000L
        /** 句与句之间排队时保留的最小停顿，相当于一个逗号的自然间隔。 */
        const val SENTENCE_GAP_MS = 120L
        /** 迟到句子的开口位置在渲染头之后留出的安全余量，大于音频缓冲区的单次处理量。 */
        const val MIN_LEAD_MS = 100L
        const val FADE_IN_MS = 6
        const val FADE_OUT_MS = 12
    }
}
