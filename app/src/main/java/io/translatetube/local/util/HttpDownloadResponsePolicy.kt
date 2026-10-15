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