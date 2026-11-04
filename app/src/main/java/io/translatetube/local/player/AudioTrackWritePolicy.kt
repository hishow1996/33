package io.translatetube.local.player

/**
 * Completes blocking PCM writes even when an OEM AudioTrack briefly reports zero progress.
 * A bounded retry prevents a broken audio sink from creating an infinite busy loop.
 */
internal object AudioTrackWritePolicy {
    const val MAX_ZERO_PROGRESS_RETRIES = 5

    fun writeFully(
        sampleCount: Int,
        write: (offset: Int, remaining: Int) -> Int,
        pauseAfterZeroProgress: () -> Unit = { Thread.sleep(5L) },
    ): Int {
        if (sampleCount < 0) return -1
        var offset = 0
        var zeroProgressRetries = 0
        while (offset < sampleCount) {
            val remaining = sampleCount - offset
            val written = write(offset, remaining)
            if (written < 0) return written
            if (written == 0) {
                if (zeroProgressRetries >= MAX_ZERO_PROGRESS_RETRIES) return offset
                zeroProgressRetries++
                pauseAfterZeroProgress()
                continue
            }
            if (written > remaining) return offset
            offset += written
            zeroProgressRetries = 0
        }
        return offset
    }
}
