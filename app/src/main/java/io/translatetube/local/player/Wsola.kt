package io.translatetube.local.player

/**
 * WSOLA（Waveform-Similarity Overlap-Add）变速不变调。
 *
 * 配音对齐专用：把每块合成 PCM 精确拉伸/压缩到字幕窗口时长（±3% 内），
 * 语音基频不变——替代 AudioTrack.setPlaybackRate 的变调变速，这是"配音不自然"
 * 的元凶之一。纯 Kotlin 无 Android 依赖，JVM 可测。
 *
 * 参数按 24kHz 语音调优：窗口 ~42ms、输出 hop 半窗、搜索域 ±~10ms。
 */
internal object Wsola {

    private const val WINDOW = 1024
    private const val HOP_OUT = WINDOW / 2
    private const val SEARCH = 256

    /**
     * 把 [samples] 时长缩放到 [ratio] 倍（ratio>1 拉长，<1 压缩，1 恒等）。
     * 返回新长度 ≈ samples.size * ratio，采样率不变。
     */
    fun stretch(samples: FloatArray, ratio: Float): FloatArray {
        if (samples.isEmpty() || !ratio.isFinite() || ratio <= 0f || ratio == 1f) return samples
        val inLen = samples.size
        val outLen = (inLen * ratio).toInt().coerceAtLeast(WINDOW)
        val out = FloatArray(outLen)

        // 每个输出 hop 对应的输入 hop（可变相位），从 0 开始同步推进
        var inPos = 0f
        var outPos = 0
        // 上一块选定的输入锚点，用于波形相似度搜索的中心
        var anchor = 0

        val fade = FloatArray(WINDOW) { i ->
            (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / WINDOW))).toFloat()
        }

        while (outPos + WINDOW <= outLen && inPos.toInt() + WINDOW < inLen) {
            val base = inPos.toInt()
            if (outPos == 0) {
                // 首块直接搭基
                System.arraycopy(samples, base, out, 0, WINDOW)
            } else {
                // 在搜索域内找与已输出尾部最相似的输入段（最小平方差）
                val center = anchor + HOP_OUT
                var bestOffset = center
                var bestError = Float.MAX_VALUE
                val from = (center - SEARCH).coerceAtLeast(0)
                val to = (center + SEARCH).coerceAtMost(inLen - WINDOW)
                var candidate = from
                while (candidate <= to) {
                    var err = 0f
                    for (i in 0 until WINDOW step 8) {
                        // 用 fade 窗加权比较，重点对齐淡化过渡区
                        val w = fade[i]
                        val d = samples[candidate + i] - out[outPos + i]
                        err += w * d * d
                    }
                    if (err < bestError) {
                        bestError = err
                        bestOffset = candidate
                    }
                    candidate += 16
                }
                anchor = bestOffset
                // overlap-add：淡出旧尾巴 + 淡入新块
                for (i in 0 until WINDOW) {
                    val w = fade[i]
                    out[outPos + i] = out[outPos + i] * (1f - w) + samples[bestOffset + i] * w
                }
            }
            inPos += HOP_OUT / ratio
            outPos += HOP_OUT
        }
        // 收尾：剩余源尾部若有残余（压缩时），把最后一窗直接补到输出末尾避免截词
        if (ratio < 1f && outPos < outLen) {
            val tail = inLen - WINDOW
            if (tail > 0) {
                for (i in 0 until WINDOW && outPos + i < outLen) {
                    val w = fade[i]
                    out[outPos + i] = out[outPos + i] * (1f - w) + samples[tail + i] * w
                }
            }
        }
        return out
    }

    /** 估算拉伸比：让 PCM 落到 [targetMs] 时长，带 [maxRatio] 上限保护自然度。 */
    fun ratioFor(sampleCount: Int, sampleRate: Int, targetMs: Long, maxRatio: Float = 1.5f): Float {
        if (sampleCount <= 0 || sampleRate <= 0 || targetMs <= 0) return 1f
        val currentMs = sampleCount * 1000f / sampleRate
        val ratio = targetMs / currentMs
        // 压缩（语速加快）超过上限会明显机械；拉伸（放慢）很少需要，同样封顶
        return ratio.coerceIn(1f / maxRatio, maxRatio)
    }
}
