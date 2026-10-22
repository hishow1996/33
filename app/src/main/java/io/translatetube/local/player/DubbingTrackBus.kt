package io.translatetube.local.player

/**
 * 配音 PCM 总线。各句按媒体时间预挂载，由 Media3 音频处理器按输入缓冲区的真实时间戳读取。
 *
 * 块在后台生产线程重采样、排序和去重；渲染线程只遍历有交集的块并累加样本，
 * 不在读侧排序，也不为每个块分配中间缓冲区。
 */
class DubbingTrackBus {

    private class Block(
        val cueKey: String?,
        val mediaTimeUs: Long,
        val startFrame: Long,
        val samples: FloatArray,
    ) {
        val endFrame: Long = startFrame + samples.size.toLong()
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
    )

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
    )

    private fun feedInternal(
        cueKey: String?,
        mediaTimeUs: Long,
        samples: FloatArray,
        sourceSampleRate: Int,
        expectedGeneration: Long?,
        isStillCurrent: (() -> Boolean)?,
    ): Boolean {
        if (samples.isEmpty() || sourceSampleRate <= 0 || mediaTimeUs < 0L) return false
        val snapshot = synchronized(stateLock) {
            if (!dubbingEnabled) return false
            if (expectedGeneration != null && expectedGeneration != contentGeneration) return false
            Triple(contentGeneration, sinkSampleRate, latestReadUs)
        }
        if (isStillCurrent?.let { runCatching(it).getOrDefault(false) } == false) return false

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

        if (isStillCurrent?.let { runCatching(it).getOrDefault(false) } == false) return false
        synchronized(stateLock) {
            if (!dubbingEnabled || contentGeneration != snapshot.first) return false
            if (expectedGeneration != null && contentGeneration != expectedGeneration) return false
            if (isStillCurrent?.let { runCatching(it).getOrDefault(false) } == false) return false

            val readHead = latestReadUs
            val cutoffUs = if (readHead == Long.MIN_VALUE) Long.MIN_VALUE
                else (readHead - RETAIN_PAST_MS * 1_000L).coerceAtLeast(0L)
            val cutoffFrame = if (cutoffUs == Long.MIN_VALUE) Long.MIN_VALUE
                else frameAtUs(cutoffUs, targetRate)
            val next = ArrayList<Block>(installed.size + 1)
            for (block in installed) {
                val sameCue = cueKey != null && block.cueKey == cueKey
                val stillRelevant = cutoffFrame == Long.MIN_VALUE || block.endFrame > cutoffFrame
                if (!sameCue && stillRelevant) next.add(block)
            }

            val startFrame = frameAtUs(mediaTimeUs, targetRate)
            val endFrame = startFrame + resampled.size.toLong()
            if (cutoffFrame != Long.MIN_VALUE && endFrame <= cutoffFrame) {
                installed = next.toTypedArray()
                return false
            }
            next.add(Block(cueKey, mediaTimeUs, startFrame, resampled))
            next.sortWith(compareBy<Block> { it.startFrame }.thenBy { it.mediaTimeUs })
            installed = next.toTypedArray()
        }
        return true
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

        for (block in blocks) {
            if (block.startFrame >= windowEndFrame) break
            if (block.endFrame <= windowStartFrame) continue
            val destinationStart = (block.startFrame - windowStartFrame).coerceAtLeast(0L).toInt()
            val sourceStart = (windowStartFrame - block.startFrame).coerceAtLeast(0L).toInt()
            val copyCount = minOf(count - destinationStart, block.samples.size - sourceStart)
            if (copyCount <= 0) continue
            var i = 0
            while (i < copyCount) {
                out[destinationStart + i] += block.samples[sourceStart + i]
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

    private companion object {
        const val RETAIN_PAST_MS = 5_000L
    }
}
