package io.translatetube.local.tts

/**
 * Normalize Android system-TTS output to the same 16 kHz rate used by the playback path before
 * it enters the PCM cache. This avoids upsampling to an intermediate rate and then downsampling
 * again for AudioTrack. The cap prevents a second large PCM allocation on long cues.
 */
internal object SystemTtsOutputAudioPolicy {
    const val TARGET_SAMPLE_RATE = 16_000
    const val CACHE_SIGNATURE = "system-tts-resample-16k-v2"
    const val MAX_RESAMPLED_SAMPLES = 4_000_000

    fun targetSampleCount(
        inputSampleCount: Int,
        inputSampleRate: Int,
        targetSampleRate: Int = TARGET_SAMPLE_RATE,
    ): Int? {
        if (inputSampleCount <= 0 || inputSampleRate !in 8_000..192_000 ||
            targetSampleRate !in 8_000..192_000
        ) return null
        val estimated = inputSampleCount.toDouble() * targetSampleRate.toDouble() /
            inputSampleRate.toDouble()
        if (!estimated.isFinite() || estimated < 1.0 ||
            estimated > MAX_RESAMPLED_SAMPLES.toDouble()
        ) return null
        return kotlin.math.round(estimated).toInt().coerceAtLeast(1)
    }

    fun normalize(audio: TtsAudio, targetSampleRate: Int = TARGET_SAMPLE_RATE): TtsAudio {
        if (audio.samples.isEmpty() || audio.sampleRate == targetSampleRate) return audio
        val outputCount = targetSampleCount(audio.samples.size, audio.sampleRate, targetSampleRate)
            ?: return audio
        val output = FloatArray(outputCount)
        val scale = audio.sampleRate.toDouble() / targetSampleRate.toDouble()
        val source = audio.samples
        for (index in output.indices) {
            val sourcePosition = index.toDouble() * scale
            val left = sourcePosition.toInt().coerceIn(0, source.lastIndex)
            val right = (left + 1).coerceAtMost(source.lastIndex)
            val fraction = (sourcePosition - left.toDouble()).toFloat().coerceIn(0f, 1f)
            val a = source[left].takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            val b = source[right].takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            output[index] = (a + (b - a) * fraction).coerceIn(-1f, 1f)
        }
        TtsPcmSanitizer.sanitizeInPlace(output)
        return TtsAudio(output, targetSampleRate)
    }
}
