package io.translatetube.local.player

/**
 * Media3 hands AudioSink.handleBuffer a *renderer* timestamp: the media time plus the stream
 * offset reported through AudioSink.setOutputStreamOffsetUs (the first period starts at 10^12 us).
 * Subtitle cues, and therefore the dubbing bus, live on the plain media timeline, so the offset
 * has to be removed before the mixer looks up queued speech. Without this the mix window never
 * overlaps any cue and the dubbing stays completely silent.
 */
internal object DubbingSinkTimestamp {
    /** Same value as androidx.media3.common.C.TIME_UNSET; kept local so this stays pure JVM. */
    private const val TIME_UNSET = Long.MIN_VALUE + 1
    private const val MAX_NEGATIVE_MEDIA_TIME_US = 1_000_000L
    const val INVALID_US = -1L

    /** Returns the media time in microseconds, or [INVALID_US] when it cannot be trusted. */
    fun toMediaTimeUs(presentationTimeUs: Long, outputStreamOffsetUs: Long): Long {
        if (presentationTimeUs == TIME_UNSET || presentationTimeUs == Long.MIN_VALUE) return INVALID_US
        if (outputStreamOffsetUs == TIME_UNSET || outputStreamOffsetUs == Long.MIN_VALUE) return INVALID_US
        val mediaUs = presentationTimeUs - outputStreamOffsetUs
        return when {
            mediaUs >= 0L -> mediaUs
            // Streams may begin with a few ms of negative timestamps; treat them as time zero.
            mediaUs >= -MAX_NEGATIVE_MEDIA_TIME_US -> 0L
            else -> INVALID_US
        }
    }
}
