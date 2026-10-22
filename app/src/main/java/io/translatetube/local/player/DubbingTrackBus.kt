package io.translatetube.local.player

/**
 * 配音 PCM 总线（媒体时钟对位）。
 *
 * 混流架构的核心数据结构：合成层把每块配音 PCM 挂到「它该出现的媒体时间」上，
 * [DubbingAudioProcessor] 在 ExoPlayer 音频渲染管线里按同一媒体时钟逐窗取出相加。
 * 配音由此与视频共用一个播放时钟：seek/暂停/倍速天然跟随，不再手工追赶。
 *
 * 时钟基点：ExoPlayer seek/rebuffer 会导致音频流 flush；[setStreamStartUs] 在
 * flush 后由播放层设一次（= player.currentPosition），此后媒体时间由流内样本数
 * 外推，误差为常数级（±50ms 内无感）。
 *
 * 线程模型：feed、reset 和模式开关通过 stateLock 串行发布状态；readMixWindow
 * 在锁内合并待处理块并获取快照，逐样本混音在锁外完成，避免长时间占用渲染线程。
 */
class DubbingTrackBus {

    /** 挂载中的块（挂载时间升序投递；读取侧按媒体时间归并）。 */
    private class Block(val mediaTimeUs: Long, val samples: FloatArray)

    private val stateLock = Any()
    private val newBlocks = java.util.concurrent.ConcurrentLinkedQueue<Block>()
    @Volatile private var installed: Array<Block> = emptyArray()
    @Volatile private var sinkSampleRate = 24_000
    /** Mode changes invalidate in-flight resampling and reads as well as queued PCM blocks. */
    @Volatile private var dubbingEnabled = true
    @Volatile private var contentGeneration = 0L

    /** flush 后的流头媒体时间（微秒）；-1 表示未校准。 */
    @Volatile private var streamStartUs = -1L

    /** 重采样后写入；挂载块长度超出窗口的部分在读取时自然丢弃（绝不让下一句排队）。 */
    fun configure(sampleRate: Int) {
        if (sampleRate > 0) sinkSampleRate = sampleRate
    }

    /** ExoPlayer 音频流 flush 后重设流头（微秒媒体时间）。 */
    fun setStreamStartUs(mediaUs: Long) {
        streamStartUs = mediaUs
    }

    fun streamStart(): Long = streamStartUs

    /** 挂一块配音 PCM；[samples] 按源采样率 [sourceSampleRate] 给出，内部线性重采样到 sink 率。 */
    fun feed(mediaTimeUs: Long, samples: FloatArray, sourceSampleRate: Int) {
        if (samples.isEmpty() || sourceSampleRate <= 0) return
        // Capture mode and generation atomically before resampling.
        val expectedGeneration = synchronized(stateLock) {
            if (!dubbingEnabled) return
            contentGeneration
        }
        // A toggle/seek while resampling must prevent this old result from being re-enqueued.
        val target = sinkSampleRate
        val resampled = if (target == sourceSampleRate || target <= 0) samples else run {
            val ratio = sourceSampleRate.toDouble() / target
            val outLen = (samples.size / ratio).toInt().coerceAtLeast(1)
            FloatArray(outLen) { i ->
                val pos = i * ratio
                val low = pos.toInt().coerceAtMost(samples.size - 1)
                val high = (low + 1).coerceAtMost(samples.size - 1)
                val frac = (pos - low).toFloat()
                samples[low] * (1f - frac) + samples[high] * frac
            }
        }
        synchronized(stateLock) {
            if (dubbingEnabled && contentGeneration == expectedGeneration) {
                newBlocks.add(Block(mediaTimeUs, resampled))
            }
        }
    }

    /**
     * Gate audible output immediately and discard all already queued/installed voice PCM.
     * Keep the stream clock calibrated so original audio and video continue uninterrupted.
     */
    fun setDubbingEnabled(enabled: Boolean) {
        synchronized(stateLock) {
            dubbingEnabled = enabled
            contentGeneration += 1L
            newBlocks.clear()
            installed = emptyArray()
        }
    }

    /** seek/换曲/停播：清空总线和校准。 */
    fun reset() {
        synchronized(stateLock) {
            contentGeneration += 1L
            newBlocks.clear()
            installed = emptyArray()
            streamStartUs = -1L
        }
    }

    /**
     * 读取 [mediaTimeUs, mediaTimeUs + count/sinkRate) 的配音窗（含跨块拼接）。
     * 返回 count 长度的 FloatArray（无声处为 0）。窗口落后于已挂块的丢弃超窗样本：
     * 每条都配到自己的窗口为止，绝不挤占下一句的开始时间。
     */
    fun readMixWindow(mediaTimeUs: Long, count: Int): FloatArray {
        val out = FloatArray(count) { 0f }
        var readGeneration = -1L
        var rate = sinkSampleRate
        val blocks = synchronized(stateLock) {
            if (!dubbingEnabled) return out
            installPendingLocked()
            readGeneration = contentGeneration
            rate = sinkSampleRate
            installed
        }
        if (blocks.isEmpty()) return out
        var blockIdx = 0
        // 跳过完全在窗口之前的块（其播放已过）
        while (blockIdx < blocks.size && blocks[blockIdx].mediaTimeUs + blocks[blockIdx].samples.size * 1_000_000L / rate <= mediaTimeUs) {
            blockIdx++
        }
        while (blockIdx < blocks.size && blocks.isNotEmpty()) {
            val block = blocks[blockIdx]
            val blockEndUs = block.mediaTimeUs + block.samples.size * 1_000_000L / rate
            if (block.mediaTimeUs >= mediaTimeUs + count * 1_000_000L / rate) break
            val blockStartUs = block.mediaTimeUs
            for (i in 0 until count) {
                val sampleUs = mediaTimeUs + i * 1_000_000L / rate
                if (sampleUs < blockStartUs || sampleUs >= blockEndUs) continue
                val offsetInBlock = ((sampleUs - blockStartUs) * rate / 1_000_000L).toInt()
                if (offsetInBlock < block.samples.size) {
                    out[i] = block.samples[offsetInBlock]
                }
            }
            blockIdx++
        }
        // If disable/re-enable happened during this read, discard this stale window too.
        if (!dubbingEnabled || contentGeneration != readGeneration) out.fill(0f)
        return out
    }

    /** 消费完毕的块（窗口已完全越过）随 install 淘汰，防内存增长。 */
    private fun installPendingLocked() {
        var dirty = false
        while (true) {
            val b = newBlocks.peek() ?: break
            dirty = true
            newBlocks.poll()
            val arr = installed
            val merged = arr + b
            java.util.Arrays.sort(merged, compareBy { it.mediaTimeUs })
            installed = merged
        }
        if (dirty) {
            // 顺手淘汰：读侧推进由调用频率决定，这里按流头粗剪（保守：保留流头前 5 秒）
            val head = streamStartUs
            if (head > 0) {
                installed = installed.filter { it.mediaTimeUs + it.samples.size * 1_000_000L / sinkSampleRate > head - 5_000_000L }.toTypedArray()
            }
        }
    }
}
