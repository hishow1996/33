package io.translatetube.local.asr

import kotlin.math.ceil
import kotlin.math.floor

/** Incremental linear resampler that preserves interpolation across MediaCodec buffer boundaries. */
internal class Pcm16StreamingResampler(
    private val sourceRate: Int,
    private val targetRate: Int,
) {
    // Keep a single output chunk bounded; the caller streams subsequent chunks to disk.
    // Eight million PCM16 samples require 16 MiB, already a large temporary allocation.
    private companion object {
        const val MAX_OUTPUT_CHUNK_SAMPLES = 8 * 1024 * 1024
    }

    private val ratio: Double
    private var inputSamples = 0L
    private var nextOutputSample = 0L
    private var pendingSamples = ShortArray(0)
    private var finished = false

    init {
        require(sourceRate in 1_000..384_000) { "不支持的输入采样率：${sourceRate}" }
        require(targetRate in 1_000..384_000) { "不支持的目标采样率：${targetRate}" }
        ratio = sourceRate.toDouble() / targetRate
    }

    fun process(input: ShortArray, checkActive: () -> Unit = {}): ShortArray {
        check(!finished) { "重采样器已结束" }
        checkActive()
        if (input.isEmpty()) return ShortArray(0)

        // Keep every unconsumed input sample from the next output's left interpolation point.
        // This handles tiny buffers where the first output cannot be emitted until later chunks.
        val chunkStart = inputSamples - pendingSamples.size
        val combined = if (pendingSamples.isEmpty()) {
            input
        } else {
            ShortArray(pendingSamples.size + input.size).also {
                pendingSamples.copyInto(it)
                input.copyInto(it, destinationOffset = pendingSamples.size)
            }
        }
        val newInputCount = inputSamples + input.size
        val targetCountNow = (newInputCount.toDouble() / ratio).toInt().toLong()
        val estimatedOutputCount = ceil(input.size.toDouble() / ratio)
        if (!estimatedOutputCount.isFinite() ||
            estimatedOutputCount > (MAX_OUTPUT_CHUNK_SAMPLES - 4).toDouble()
        ) {
            throw IllegalArgumentException(
                "单个重采样输入块过大，预计输出超过 ${MAX_OUTPUT_CHUNK_SAMPLES} 个采样点；请拆分为更小的音频块"
            )
        }
        val initialCapacity = estimatedOutputCount.toInt().coerceAtLeast(1) + 4
        var output = ShortArray(initialCapacity)
        var produced = 0

        while (nextOutputSample < targetCountNow) {
            if ((produced and 0x1fff) == 0) checkActive()
            val position = nextOutputSample.toDouble() * ratio
            val leftIndex = floor(position).toLong()
            val rightIndex = leftIndex + 1L
            if (rightIndex >= newInputCount) break
            val leftOffset = (leftIndex - chunkStart).toInt()
            val rightOffset = (rightIndex - chunkStart).toInt()
            check(leftOffset in combined.indices && rightOffset in combined.indices) {
                "重采样器缺少跨块输入样本"
            }
            val left = combined[leftOffset]
            val right = combined[rightOffset]
            val fraction = position - leftIndex
            if (produced == output.size) output = output.copyOf(output.size * 2)
            output[produced++] = (left + (right - left) * fraction).toInt().toShort()
            nextOutputSample++
        }

        inputSamples = newInputCount
        if (inputSamples > 0L) {
            val nextLeftIndex = floor(nextOutputSample.toDouble() * ratio).toLong()
            val keepFrom = nextLeftIndex.coerceIn(chunkStart, inputSamples - 1L)
            val keepOffset = (keepFrom - chunkStart).toInt()
            pendingSamples = combined.copyOfRange(keepOffset, combined.size)
        } else {
            pendingSamples = ShortArray(0)
        }
        return output.copyOf(produced)
    }

    fun finish(checkActive: () -> Unit = {}): ShortArray {
        check(!finished) { "重采样器已结束" }
        checkActive()
        finished = true
        if (inputSamples == 0L) return ShortArray(0)

        // Match the previous whole-array implementation's Double.toInt() output length.
        val targetCount = (inputSamples.toDouble() / ratio).toInt().toLong()
        val remaining = (targetCount - nextOutputSample).coerceAtLeast(0L)
        require(remaining <= Int.MAX_VALUE.toLong()) { "重采样输出过长" }
        val tail = ShortArray(remaining.toInt())
        val last = pendingSamples.lastOrNull()
            ?: error("重采样器缺少最后一个输入样本")
        for (i in tail.indices) {
            if ((i and 0x1fff) == 0) checkActive()
            // Any deferred tail positions clamp to the final source sample, matching the old algorithm.
            tail[i] = last
            nextOutputSample++
        }
        return tail
    }
}
