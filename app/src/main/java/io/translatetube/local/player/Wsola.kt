package io.translatetube.local.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * WSOLA（Waveform-Similarity Overlap-Add）变速不变调。
 *
 * 配音对齐专用：把每块合成 PCM 压缩/拉伸到目标时长，语音基频不变——替代
 * AudioTrack.setPlaybackRate 的变调变速，这是"配音不自然"的元凶之一。
 * 纯 Kotlin 无 Android 依赖，JVM 可测。
 *
 * 实现要点（与经典 WSOLA 一致）：
 * - 帧长 [WINDOW]、输出帧移为半帧，使用周期 Hann 窗：相邻两帧的窗口之和恒为 1，
 *   叠加后电平恒定（旧实现的"交叉淡化"权重不互补，会让电平每 ~20ms 起伏一次，听感发颤）。
 * - 选段时只比较"重叠半帧"与"上一段自然延续"的归一化互相关，避免偏向低能量片段。
 * - 首帧前半、末帧后半不加窗，开头结尾不会被无故淡出。
 */
internal object Wsola {

    private const val WINDOW = 1024
    private const val HOP = WINDOW / 2
    /** 以名义位置为中心的搜索半径（采样点）。 */
    private const val SEARCH = 256
    /** 搜索步长与相关计算步长：精度与速度的折中，3 秒语音耗时约毫秒级。 */
    private const val SEARCH_STEP = 4
    private const val CORR_STRIDE = 2

    private val hann = FloatArray(WINDOW) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / WINDOW))).toFloat()
    }

    /**
     * 把 [samples] 时长缩放到 [ratio] 倍（ratio>1 拉长，<1 压缩，1 恒等）。
     * 返回新长度 ≈ samples.size * ratio，采样率不变。
     */
    fun stretch(samples: FloatArray, ratio: Float): FloatArray {
        if (samples.isEmpty() || !ratio.isFinite() || ratio <= 0f || ratio == 1f) return samples
        val inLen = samples.size
        val outLen = (inLen.toDouble() * ratio)
            .toLong()
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

        // WSOLA requires a full analysis window in both arrays. Extremely short subtitles and
        // compressed outputs do not have one, so use bounded interpolation rather than returning
        // a silent buffer or attempting an invalid overlap.
        if (inLen <= WINDOW || outLen <= WINDOW) {
            return linearResample(samples, outLen)
        }

        val out = FloatArray(outLen)
        val maxOffset = inLen - WINDOW
        // 覆盖 outLen 所需的帧数；最后一帧的右端可能越过 outLen，写入时截断。
        val frameCount = ((outLen - WINDOW + HOP - 1) / HOP) + 1
        val lastFrame = frameCount - 1

        var previousPosition = 0
        for (frame in 0 until frameCount) {
            // 名义分析位置线性映射：首帧对齐输入开头，末帧对齐输入末尾，整段内容被完整覆盖。
            val nominal = if (lastFrame == 0) 0 else
                (frame.toLong() * maxOffset / lastFrame).toInt()
            val position = when (frame) {
                0 -> 0
                lastFrame -> maxOffset
                else -> bestPosition(samples, previousPosition + HOP, nominal, maxOffset)
            }
            val outStart = frame * HOP
            val writable = minOf(WINDOW, outLen - outStart)
            for (n in 0 until writable) {
                val weight = when {
                    frame == 0 && n < HOP -> 1f      // 首帧前半：无前帧可互补，不淡入
                    frame == lastFrame && n >= HOP -> 1f // 末帧后半：无后帧可互补，不淡出
                    else -> hann[n]
                }
                out[outStart + n] += samples[position + n] * weight
            }
            previousPosition = position
        }
        return out
    }

    /**
     * 在 [nominal] ± [SEARCH] 内找与"上一段自然延续"[continuation] 起始的半帧最相似的位置。
     * 相似度用归一化互相关，不受片段能量大小影响。
     */
    private fun bestPosition(
        samples: FloatArray,
        continuation: Int,
        nominal: Int,
        maxOffset: Int,
    ): Int {
        val from = (nominal - SEARCH).coerceIn(0, maxOffset)
        val to = (nominal + SEARCH).coerceIn(0, maxOffset)
        // continuation + HOP <= 上一段位置 + WINDOW <= samples.size，范围必然有效。
        if (continuation < 0 || continuation + HOP > samples.size) return nominal.coerceIn(from, to)

        var referenceEnergy = 0.0
        var i = 0
        while (i < HOP) {
            val r = samples[continuation + i].toDouble()
            referenceEnergy += r * r
            i += CORR_STRIDE
        }
        var best = nominal.coerceIn(from, to)
        var bestScore = -Double.MAX_VALUE
        var candidate = from
        while (candidate <= to) {
            var cross = 0.0
            var energy = 0.0
            var k = 0
            while (k < HOP) {
                val c = samples[candidate + k].toDouble()
                cross += c * samples[continuation + k]
                energy += c * c
                k += CORR_STRIDE
            }
            val score = cross / sqrt(energy * referenceEnergy + 1e-9)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            candidate += SEARCH_STEP
        }
        return best.coerceIn(0, maxOffset)
    }

    /** Safe fallback for cues shorter than one WSOLA window. */
    private fun linearResample(samples: FloatArray, outLen: Int): FloatArray {
        if (samples.isEmpty() || outLen <= 0) return FloatArray(0)
        if (samples.size == 1) return FloatArray(outLen) { samples[0] }
        if (outLen == 1) return floatArrayOf(samples[0])
        val output = FloatArray(outLen)
        val sourceScale = (samples.lastIndex).toDouble() / output.lastIndex
        for (i in output.indices) {
            val sourcePosition = i * sourceScale
            val left = sourcePosition.toInt().coerceIn(0, samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            output[i] = samples[left] * (1f - fraction) + samples[right] * fraction
        }
        return output
    }
}
