package io.translatetube.local.player

import kotlin.math.floor
import kotlin.math.roundToInt

internal data class DubbingOutputPcm(
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * Compatibility normalization for TTS playback on older Android audio stacks.
 * Keep output mono and at or below the source's duration. 16 kHz PCM is widely supported by
 * Android speech/audio paths and reduces static AudioTrack buffer size versus 22.05/24/48 kHz.
 */
internal object DubbingOutputPcmPolicy {
    const val PREFERRED_SAMPLE_RATE = 16_000
    private const val MIN_SUPPORTED_RATE = 8_000
    private const val MAX_SUPPORTED_RATE = 192_000

    fun normalize(samples: FloatArray, sampleRate: Int): DubbingOutputPcm {
        require(sampleRate in MIN_SUPPORTED_RATE..MAX_SUPPORTED_RATE) {
            "不支持的配音采样率：${sampleRate} Hz"
        }
        if (samples.isEmpty()) return DubbingOutputPcm(samples, sampleRate)
        if (sampleRate <= PREFERRED_SAMPLE_RATE) {
            // Keep the zero-copy path for normal TTS output, but don't send out-of-range floats
            // or NaN/Infinity directly to PCM_FLOAT AudioTrack. PCM_16BIT already clips these
            // values, so sanitizing here also makes output consistent on devices that support
            // the float track.
            var needsSanitizing = false
            for (sample in samples) {
                if (!sample.isFinite() || sample < -1.0f || sample > 1.0f) {
                    needsSanitizing = true
                    break
                }
            }
            if (!needsSanitizing) return DubbingOutputPcm(samples, sampleRate)
            val sanitized = FloatArray(samples.size) { index ->
                val sample = samples[index]
                if (sample.isFinite()) sample.coerceIn(-1.0f, 1.0f) else 0.0f
            }
            return DubbingOutputPcm(sanitized, sampleRate)
        }
        val outputCountLong = (samples.size.toLong() * PREFERRED_SAMPLE_RATE + sampleRate / 2L) / sampleRate
        require(outputCountLong in 1L..DubbingPcmValidation.MAX_SAMPLE_COUNT.toLong()) {
            "配音重采样后的数据量超过安全上限"
        }
        val outputCount = outputCountLong.toInt()
        val output = FloatArray(outputCount)
        val scale = sampleRate.toDouble() / PREFERRED_SAMPLE_RATE
        for (index in output.indices) {
            val sourcePosition = index * scale
            val left = floor(sourcePosition).toInt().coerceIn(0, samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            val fraction = (sourcePosition - left).toFloat().coerceIn(0f, 1f)
            val interpolated = samples[left] * (1f - fraction) + samples[right] * fraction
            output[index] = if (interpolated.isFinite()) interpolated.coerceIn(-1f, 1f) else 0f
        }
        return DubbingOutputPcm(output, PREFERRED_SAMPLE_RATE)
    }
}
