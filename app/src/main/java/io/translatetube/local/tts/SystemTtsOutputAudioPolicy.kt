package io.translatetube.local.tts

/**
 * Normalize Android system-TTS output to 24 kHz before it enters the PCM cache. Several OEM
 * Android audio stacks are less reliable with 16 kHz mono static AudioTracks; 24 kHz also matches
 * the main local TTS output rate, avoiding repeated conversions in the common playback path. The
 * cap prevents a second large PCM allocation on long cues.
 */
internal object SystemTtsOutputAudioPolicy {
    const val TARGET_SAMPLE_RATE = 24_000
    const val CACHE_SIGNATURE = "system-tts-resample-24k-v5-oem-audiotrack-compat"
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

    fun normalize(
        audio: TtsAudio,
        targetSampleRate: Int = TARGET_SAMPLE_RATE,
        checkActive: () -> Unit = {},
    ): TtsAudio {
        checkActive()
        if (audio.samples.isEmpty()) return audio
        if (audio.sampleRate == targetSampleRate) {
            var needsSanitizing = false
            for (index in audio.samples.indices) {
                if ((index and 0x1fff) == 0) checkActive()
                val sample = audio.samples[index]
                if (!sample.isFinite() || sample < -1f || sample > 1f) {
                    needsSanitizing = true
                    break
                }
            }
            if (!needsSanitizing) return audio
            val clean = FloatArray(audio.samples.size)
            for (index in audio.samples.indices) {
                if ((index and 0x1fff) == 0) checkActive()
                val sample = audio.samples[index]
                clean[index] = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            }
            checkActive()
            return TtsAudio(clean, audio.sampleRate)
        }
        val outputCount = targetSampleCount(audio.samples.size, audio.sampleRate, targetSampleRate)
            ?: return audio
        val output = FloatArray(outputCount)
        val scale = audio.sampleRate.toDouble() / targetSampleRate.toDouble()
        val source = audio.samples
        for (index in output.indices) {
            if ((index and 0x1fff) == 0) checkActive()
            val sourcePosition = index.toDouble() * scale
            val left = sourcePosition.toInt().coerceIn(0, source.lastIndex)
            val right = (left + 1).coerceAtMost(source.lastIndex)
            val fraction = (sourcePosition - left.toDouble()).toFloat().coerceIn(0f, 1f)
            val a = source[left].takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            val b = source[right].takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            output[index] = (a + (b - a) * fraction).coerceIn(-1f, 1f)
        }
        checkActive()
        TtsPcmSanitizer.sanitizeInPlace(output)
        checkActive()
        return TtsAudio(output, targetSampleRate)
    }

    /**
     * Make system-TTS output follow the same edge-silence policy as local neural voices.
     * Trimming after rate conversion keeps the 25 ms safety margin stable across source rates.
     */
    fun prepareForPlayback(
        audio: TtsAudio,
        targetSampleRate: Int = TARGET_SAMPLE_RATE,
        checkActive: () -> Unit = {},
    ): TtsAudio {
        checkActive()
        val normalized = normalize(audio, targetSampleRate, checkActive)
        if (normalized.samples.isEmpty()) return normalized
        val trimmed = TtsEdgeSilenceTrimmer.trim(
            normalized.samples,
            normalized.sampleRate,
            checkActive,
        )
        checkActive()
        return if (trimmed === normalized.samples) normalized else normalized.copy(samples = trimmed)
    }
}
