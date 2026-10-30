package io.translatetube.local.translate

/** Validates a resumed HTTP range before appending it to a saved model fragment. */
internal object ModelDownloadRangePolicy {
    private val contentRangePattern = Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+)$""")

    fun matches(header: String?, expectedStart: Long, expectedTotal: Long): Boolean {
        if (header.isNullOrBlank() || expectedStart <= 0L || expectedTotal <= expectedStart) return false
        val match = contentRangePattern.matchEntire(header.trim()) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start == expectedStart && total == expectedTotal && end == expectedTotal - 1L
    }

    /** A full-size temporary file can be validated and installed without another HTTP request. */
    fun isCompletePart(partBytes: Long, expectedBytes: Long): Boolean =
        expectedBytes > 0L && partBytes == expectedBytes

    /** Reject an oversized response before writing it to disk. */
    fun canAcceptBytes(currentBytes: Long, incomingBytes: Long, expectedBytes: Long): Boolean =
        expectedBytes > 0L && currentBytes >= 0L && incomingBytes >= 0L &&
            currentBytes <= expectedBytes && incomingBytes <= expectedBytes - currentBytes
}