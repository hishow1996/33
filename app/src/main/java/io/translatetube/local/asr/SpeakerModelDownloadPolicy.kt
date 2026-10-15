package io.translatetube.local.asr

/** Integrity checks for remotely downloaded speaker-diarization model assets. */
internal object SpeakerModelDownloadPolicy {
    const val MAX_SEGMENTATION_MODEL_BYTES = 30L * 1024L * 1024L

    /** These model endpoints do not send Range requests; 206 must not be installed as a full asset. */
    fun isFullResponse(statusCode: Int): Boolean = statusCode == 200

    /** HTTP uses -1 only when Content-Length is unknown. Other negative and zero lengths are invalid. */
    fun isValidExpectedLength(expectedBytes: Long, maxBytes: Long): Boolean =
        maxBytes > 0L && (expectedBytes == -1L || expectedBytes in 1L..maxBytes)

    fun isCompletePayload(expectedBytes: Long, actualBytes: Long, maxBytes: Long): Boolean =
        maxBytes > 0L &&
            actualBytes in 1L..maxBytes &&
            (expectedBytes == -1L || expectedBytes == actualBytes)

    /** Never install a truncated tar entry, even if it exceeds a plausible minimum size. */
    fun isCompleteArchiveEntry(declaredBytes: Long, actualBytes: Long, maxBytes: Long): Boolean =
        maxBytes > 0L && declaredBytes in 1L..maxBytes && actualBytes == declaredBytes
}
