package io.translatetube.local.player

import java.io.File

/**
 * Lightweight fingerprint used before removing a cache file that was inspected as corrupt.
 * Cache writers publish with atomic rename, so a reader must not delete a newer replacement
 * merely because the older file it opened failed validation.
 */
internal data class DubbingCacheFileStamp(
    val length: Long,
    val lastModifiedMs: Long,
) {
    fun stillMatches(file: File): Boolean =
        file.isFile &&
            file.length() == length &&
            file.lastModified() == lastModifiedMs

    companion object {
        fun capture(file: File): DubbingCacheFileStamp? {
            if (!file.isFile) return null
            val stamp = DubbingCacheFileStamp(file.length(), file.lastModified())
            // Avoid returning a mixed fingerprint if an atomic replacement happened while
            // reading the two metadata fields.
            return stamp.takeIf { it.stillMatches(file) }
        }
    }
}
