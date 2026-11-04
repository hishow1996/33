package io.translatetube.local.util

/**
 * Validation for HTTP GET downloads that do not request byte ranges.
 *
 * Only HTTP 200 represents a complete response here. Content-Length -1 is the single
 * unknown-length sentinel; 0 or any other negative value must not be mistaken for "unknown".
 */
internal object HttpDownloadResponsePolicy {
    fun isCompleteGetResponse(statusCode: Int): Boolean = statusCode == 200

    fun isValidExpectedLength(expectedBytes: Long, maxBytes: Long): Boolean =
        maxBytes > 0L &&
            (expectedBytes == -1L || expectedBytes in 1L..maxBytes)

    fun isCompletePayload(expectedBytes: Long, actualBytes: Long, maxBytes: Long): Boolean =
        isValidExpectedLength(expectedBytes, maxBytes) &&
            actualBytes in 1L..maxBytes &&
            (expectedBytes == -1L || actualBytes == expectedBytes)
}

/**
 * A persistently slow primary release host should not hold a model install hostage while
 * healthier mirrors are available. Only judge after a meaningful sample; never repeatedly
 * abort a mirror, since slow mobile connections can be legitimate.
 */
internal object HttpDownloadSpeedPolicy {
    const val MIN_SAMPLE_BYTES = 1L * 1024L * 1024L
    const val MIN_SAMPLE_MILLIS = 8_000L
    const val MIN_BYTES_PER_SECOND = 128L * 1024L

    fun shouldFallbackFromPrimarySource(
        sourceIndex: Int,
        downloadedBytes: Long,
        elapsedMillis: Long,
    ): Boolean {
        if (sourceIndex != 0 || downloadedBytes < MIN_SAMPLE_BYTES || elapsedMillis < MIN_SAMPLE_MILLIS) {
            return false
        }
        val bytesPerSecond = downloadedBytes.toDouble() * 1_000.0 / elapsedMillis.toDouble()
        return bytesPerSecond < MIN_BYTES_PER_SECOND.toDouble()
    }
}

/** Signals a slow primary host so the existing fallback loop can retry a mirror cleanly. */
internal class SlowDownloadSourceException(
    downloadedBytes: Long,
    elapsedMillis: Long,
) : java.io.IOException(
    "官方模型源速度过慢（约 " +
        ((downloadedBytes.toDouble() * 1_000.0 / elapsedMillis.coerceAtLeast(1L)).toLong() / 1024L) +
        " KB/s），正在尝试其他下载源"
)

