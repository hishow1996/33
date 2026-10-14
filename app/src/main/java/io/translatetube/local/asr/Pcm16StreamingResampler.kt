package io.translatetube.local.asr

import kotlin.math.ceil
import kotlin.math.floor

/** Incremental linear resampler that preserves interpolation across MediaCodec buffer boundaries. */
internal class Pcm16StreamingResampler(
    private val sourceRate: Int,
    private val targetRate: Int,
) {
    private val ratio: Double
    private var inputSamples = 0L
    private var nextOutputSample = 0L
    private var previousSample: Short? = null
    private var finished = false

    init {
        require(sourceRate in 1_000..384_000) { "不支持的输入采样率：${sourceRate}" }
        require(targetRate in 1_000..384_000) { "不支持的目标采样率：${targetRate}" }
        ratio = sourceRate.toDouble() / targetRate
    }

    fun process(input: ShortArray): ShortArray {
        check(!finished) { "重采样器已结束" }
        if (input.isEmpty()) return ShortArray(0)

        val chunkStart = inputSamples
        val newInputCount = inputSamples + input.size
        val targetCountNow = (newInputCount.toDouble() / ratio).toInt().toLong()
        val estimatedOutputCount = ceil(input.size.toDouble() / ratio).toInt().coerceAtLeast(1) + 4
        var output = ShortArray(estimatedOutputCount)
        var produced = 0

        while (nextOutputSample < targetCountNow) {
            val position = nextOutputSample.toDouble() * ratio
            val leftIndex = floor(position).toLong()
            val rightIndex = leftIndex + 1L
            if (rightIndex >= newInputCount) break
            val left = sampleAt(leftIndex, chunkStart, input)
            val right = sampleAt(rightIndex, chunkStart, input)
            val fraction = position - leftIndex
            if (produced == output.size) output = output.copyOf(output.size * 2)
            output[produced++] = (left + (right - left) * fraction).toInt().toShort()
            nextOutputSample++
        }

        previousSample = input.last()
        inputSamples = newInputCount
        return output.copyOf(produced)
    }

    fun finish(): ShortArray {
        check(!finished) { "重采样器已结束" }
        finished = true
        if (inputSamples == 0L) return ShortArray(0)

        // Match the previous whole-array implementation's Double.toInt() output length.
        val targetCount = (inputSamples.toDouble() / ratio).toInt().toLong()
        val remaining = (targetCount - nextOutputSample).coerceAtLeast(0L)
        require(remaining <= Int.MAX_VALUE.toLong()) { "重采样输出过长" }
        val tail = ShortArray(remaining.toInt())
        val last = previousSample ?: return ShortArray(0)
        for (i in tail.indices) {
            // Unavailable right samples are clamped to the final source sample, as before.
            tail[i] = last
            nextOutputSample++
        }
        return tail
    }

    private fun sampleAt(index: Long, chunkStart: Long, input: ShortArray): Short {
        if (index < chunkStart) {
            check(index == chunkStart - 1L) { "重采样器缺少跨块边界样本" }
            return previousSample ?: error("重采样器缺少前一块样本")
        }
        val offset = (index - chunkStart).toInt()
        check(offset in input.indices) { "重采样器读取超出当前数据块" }
        return input[offset]
    }
}
