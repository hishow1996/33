package io.translatetube.local.tts

import kotlin.math.abs

/**
 * Trims leading/trailing silence so synthesized speech starts closer to the subtitle cue.
 * Optional cancellation checks let a replaced video stop scanning a long system-TTS buffer.
 */
internal object TtsEdgeSilenceTrimmer {
    private const val SILENCE_THRESHOLD = 0.004f
    private const val MAX_TRIM_MS = 400
    private const val KEEP_MARGIN_MS = 25
    private const val CHECK_MASK = 0x3fff

    fun trim(
        samples: FloatArray,
        sampleRate: Int,
        checkActive: () -> Unit = {},
    ): FloatArray {
        checkActive()
        if (samples.isEmpty() || sampleRate <= 0) return samples
        val maxTrim = MAX_TRIM_MS * sampleRate / 1000
        val margin = KEEP_MARGIN_MS * sampleRate / 1000

        var head = 0
        while (head < samples.size && head < maxTrim && abs(samples[head]) < SILENCE_THRESHOLD) {
            if ((head and CHECK_MASK) == 0) checkActive()
            head++
        }

        // If the remaining output is silent, preserve its duration rather than collapsing the
        // subtitle window; the caller can then report/fallback based on the validated PCM.
        var hasLoud = false
        for (index in head until samples.size) {
            if ((index and CHECK_MASK) == 0) checkActive()
            if (abs(samples[index]) >= SILENCE_THRESHOLD) {
                hasLoud = true
                break
            }
        }
        if (!hasLoud) return samples

        var tail = samples.lastIndex
        var tailScanned = 0
        while (tail > head && tailScanned < maxTrim && abs(samples[tail]) < SILENCE_THRESHOLD) {
            if ((tailScanned and CHECK_MASK) == 0) checkActive()
            tail--
            tailScanned++
        }

        val from = (head - margin).coerceAtLeast(0)
        val toExclusive = (tail + 1 + margin).coerceAtMost(samples.size)
        if (from <= 0 && toExclusive >= samples.size) return samples
        if (toExclusive <= from) return samples

        // Implement the bounded copy explicitly so cancellation can interrupt a large allocation
        // copy rather than waiting for FloatArray.copyOfRange() to finish.
        val result = FloatArray(toExclusive - from)
        var offset = 0
        while (offset < result.size) {
            checkActive()
            val count = minOf(8_192, result.size - offset)
            samples.copyInto(
                destination = result,
                destinationOffset = offset,
                startIndex = from + offset,
                endIndex = from + offset + count,
            )
            offset += count
        }
        checkActive()
        return result
    }
}
