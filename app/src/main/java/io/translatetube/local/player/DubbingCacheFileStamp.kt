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
    /** Device/inode identity where the platform exposes it; null keeps the legacy fallback. */
    val fileIdentity: String? = null,
) {
    fun stillMatches(file: File): Boolean =
        file.isFile &&
            file.length() == length &&
            file.lastModified() == lastModifiedMs &&
            (fileIdentity == null || currentFileIdentity(file) == fileIdentity)

    companion object {
        /**
         * Atomic rename can replace a cache with a same-size file whose timestamp falls in the
         * same millisecond. Length + mtime alone would then let an old validator delete the new
         * cache. Android's stat identity distinguishes the replacement without hashing the PCM.
         * Fall back to the legacy metadata pair if the filesystem/runtime exposes no identity.
         */
        private fun currentFileIdentity(file: File): String? = runCatching {
            val stat = android.system.Os.stat(file.absolutePath)
            "${stat.st_dev}:${stat.st_ino}"
        }.getOrNull()

        fun capture(file: File): DubbingCacheFileStamp? {
            if (!file.isFile) return null
            val stamp = DubbingCacheFileStamp(
                length = file.length(),
                lastModifiedMs = file.lastModified(),
                fileIdentity = currentFileIdentity(file),
            )
            // Avoid returning a mixed fingerprint if an atomic replacement happened while
            // reading metadata. On Android, the identity also catches same-length/same-mtime swaps.
            return stamp.takeIf { it.stillMatches(file) }
        }
    }
}
