package io.translatetube.local.data

/** Conservative free-space gate for downloading an archive while extracting a staged model beside it. */
internal object ModelStoragePreflightPolicy {
    const val MIB: Long = 1024L * 1024L
    const val DEFAULT_SAFETY_MARGIN_BYTES: Long = 96L * MIB

    fun requiredBytes(
        archiveBytes: Long,
        extractedBytes: Long,
        safetyMarginBytes: Long = DEFAULT_SAFETY_MARGIN_BYTES,
    ): Long {
        if (archiveBytes < 0L || extractedBytes < 0L || safetyMarginBytes < 0L) {
            return Long.MAX_VALUE
        }
        return saturatedAdd(saturatedAdd(archiveBytes, extractedBytes), safetyMarginBytes)
    }

    /** Zero means Android could not report free space; don't falsely block the download in that case. */
    fun hasEnoughSpace(usableBytes: Long, requiredBytes: Long): Boolean =
        requiredBytes >= 0L && (usableBytes <= 0L || usableBytes >= requiredBytes)

    fun minimumKnownUsableSpace(vararg candidates: Long): Long =
        candidates.filter { it > 0L }.minOrNull() ?: 0L

    fun roundedUpMiB(bytes: Long): Long =
        if (bytes <= 0L) 0L else 1L + (bytes - 1L) / MIB

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
