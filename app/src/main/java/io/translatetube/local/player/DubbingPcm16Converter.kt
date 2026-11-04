package io.translatetube.local.player

import kotlin.math.roundToInt

/** Converts normalized float PCM to signed 16-bit mono samples for OEM AudioTrack fallback. */
internal object DubbingPcm16Converter {
    fun convert(samples: FloatArray): ShortArray = ShortArray(samples.size) { index ->
        val value = samples[index].takeIf { it.isFinite() }?.coerceIn(-1.0f, 1.0f) ?: 0.0f
        (value * 32768.0f)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}
