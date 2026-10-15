package io.translatetube.local.data

import java.io.File

/** Cache accounting/cleanup for the app-owned generated-audio directories and legacy temp files. */
internal object AppCacheStoragePolicy {
    private val managedDirectoryNames = listOf("dub", "tts_tmp", "asr_audio_retry")
    private val managedTopLevelPrefixes = listOf(
        "asr_audio_",
        "translate-pcm-",
        "asr-cloud-",
        "asr-kawa-",
        "piper-model-",
    )

    fun canClear(
        isPlaying: Boolean,
        isProcessing: Boolean,
        dubbingIncomplete: Boolean,
        modelDownloadActive: Boolean = false,
    ): Boolean = !isPlaying && !isProcessing && !dubbingIncomplete && !modelDownloadActive

    /** Cache UI totals should saturate rather than wrap negative on extreme file sizes. */
    internal fun addBytesSaturated(total: Long, bytes: Long): Long {
        require(total >= 0L) { "缓存累计值不能为负数" }
        if (bytes <= 0L) return total
        return if (total > Long.MAX_VALUE - bytes) Long.MAX_VALUE else total + bytes
    }

    fun sizeBytes(cacheDir: File): Long {
        var total = 0L
        managedDirectoryNames.forEach { name ->
            val directory = File(cacheDir, name)
            if (directory.isDirectory) {
                directory.walkTopDown().filter { it.isFile }.forEach { file ->
                    total = addBytesSaturated(total, file.length().coerceAtLeast(0L))
                }
            }
        }
        cacheDir.listFiles()
            ?.filter { file ->
                file.isFile && managedTopLevelPrefixes.any { prefix -> file.name.startsWith(prefix) }
            }
            ?.forEach { file -> total = addBytesSaturated(total, file.length().coerceAtLeast(0L)) }
        return total
    }

    /** Leaves Room databases, downloaded model files and unrelated cache entries untouched. */
    fun clear(cacheDir: File): Boolean {
        var succeeded = true
        managedDirectoryNames.forEach { name ->
            val directory = File(cacheDir, name)
            if (directory.exists() && !directory.deleteRecursively() && directory.exists()) {
                succeeded = false
            }
        }
        cacheDir.listFiles()
            ?.filter { file ->
                file.isFile && managedTopLevelPrefixes.any { prefix -> file.name.startsWith(prefix) }
            }
            ?.forEach { file ->
                if (!file.delete() && file.exists()) succeeded = false
            }
        return succeeded
    }
}
