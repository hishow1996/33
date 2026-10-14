package io.translatetube.local.tts

import java.io.File

/** Removes abandoned system-TTS WAV files after a force-stop without touching active recent files. */
internal object TtsTempFilePolicy {
    const val PREFIX = "tts_"
    const val SUFFIX = ".wav"
    const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L

    fun isStale(fileName: String, lastModifiedMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX, ignoreCase = true)) return false
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs || maxAgeMs < 0L) return false
        return nowMs - lastModifiedMs > maxAgeMs
    }

    fun cleanupStale(directory: File, nowMs: Long = System.currentTimeMillis()): Int {
        val files = directory.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (file.isFile && isStale(file.name, file.lastModified(), nowMs) && file.delete()) {
                deleted++
            }
        }
        return deleted
    }
}
