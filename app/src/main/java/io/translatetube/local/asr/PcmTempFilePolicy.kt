package io.translatetube.local.asr

import java.io.File

/** Prevent abandoned PCM scratch files from consuming storage after force-stop or process death. */
internal object PcmTempFilePolicy {
    const val PREFIX = "translate-pcm-"
    const val SUFFIX = ".s16le"
    const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L

    fun isStale(fileName: String, lastModifiedMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return false
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs || maxAgeMs < 0L) return false
        return nowMs - lastModifiedMs > maxAgeMs
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
