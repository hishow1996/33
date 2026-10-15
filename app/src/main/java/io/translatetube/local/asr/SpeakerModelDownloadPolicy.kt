package io.translatetube.local.asr

/** Integrity checks for remotely downloaded speaker-diarization model assets. */
internal object SpeakerModelDownloadPolicy {
    const val MIN_SEGMENTATION_MODEL_BYTES = 1_000_000L
    const val MAX_SEGMENTATION_MODEL_BYTES = 30L * 1024L * 1024L
    const val MIN_EMBEDDING_MODEL_BYTES = 5_000_000L
    const val MAX_EMBEDDING_MODEL_BYTES = 100L * 1024L * 1024L

    fun isValidCachedSegmentationModel(isFile: Boolean, sizeBytes: Long): Boolean =
        isFile && sizeBytes in MIN_SEGMENTATION_MODEL_BYTES..MAX_SEGMENTATION_MODEL_BYTES

    fun isValidCachedEmbeddingModel(isFile: Boolean, sizeBytes: Long): Boolean =
        isFile && sizeBytes in MIN_EMBEDDING_MODEL_BYTES..MAX_EMBEDDING_MODEL_BYTES

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
