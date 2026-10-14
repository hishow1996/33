package io.translatetube.local.asr

import java.io.File

/** Prevent abandoned PCM scratch files from consuming storage after force-stop or process death. */
internal object PcmTempFilePolicy {
    const val PREFIX = "translate-pcm-"
    const val SUFFIX = ".s16le"
    const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    const val MAX_PCM_BYTES = 512L * 1024L * 1024L
    const val MIN_FREE_SPACE_BYTES = 32L * 1024L * 1024L
    const val SPACE_CHECK_INTERVAL_BYTES = 1L * 1024L * 1024L

    fun isStale(fileName: String, lastModifiedMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return false
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs || maxAgeMs < 0L) return false
        return nowMs - lastModifiedMs > maxAgeMs
    }

    fun canAppendPcm(currentBytes: Long, bytesToAppend: Long, maxBytes: Long = MAX_PCM_BYTES): Boolean {
        if (currentBytes < 0L || bytesToAppend < 0L || maxBytes <= 0L) return false
        if (currentBytes > maxBytes) return false
        return bytesToAppend <= maxBytes - currentBytes
    }

    /** Unknown storage reports are not treated as zero bytes; known free space keeps a safety reserve. */
    fun hasDiskHeadroom(
        usableSpace: Long,
        bytesToWrite: Long,
        minFreeBytes: Long = MIN_FREE_SPACE_BYTES,
    ): Boolean {
        if (usableSpace <= 0L) return true
        if (bytesToWrite < 0L || minFreeBytes < 0L) return false
        if (usableSpace < minFreeBytes) return false
        return bytesToWrite <= usableSpace - minFreeBytes
    }

    fun cleanupStale(cacheDir: File, nowMs: Long = System.currentTimeMillis()): Int {
        val files = cacheDir.listFiles() ?: return 0
        var deleted = 0
        files.forEach { file ->
            if (file.isFile && isStale(file.name, file.lastModified(), nowMs) && file.delete()) {
                deleted++
            }
        }
        return deleted
    }
}
