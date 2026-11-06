package io.translatetube.local.data

import java.io.File

/** Accounting and cleanup for app-owned media-processing caches and recovery snapshots. */
internal object AppCacheStoragePolicy {
    private val managedCacheDirectories = listOf(
        "dub",
        "tts_tmp",
        "asr_audio_retry",
        "local_asr_pcm16",
        "ready_session_snapshots",
    )
    private val managedFilesDirectories = listOf(
        "in_progress_processing",
        "ready_session_snapshots",
    )
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

    internal fun addBytesSaturated(total: Long, bytes: Long): Long {
        require(total >= 0L) { "缓存累计值不能为负数" }
        if (bytes <= 0L) return total
        return if (total > Long.MAX_VALUE - bytes) Long.MAX_VALUE else total + bytes
    }

    fun sizeBytes(
        cacheDir: File,
        noBackupFilesDir: File? = null,
        filesDir: File? = null,
    ): Long {
        var total = 0L
        managedCacheDirectories.forEach { name ->
            total = addDirectoryBytesSaturated(total, File(cacheDir, name))
        }
        cacheDir.listFiles()
            ?.filter { file ->
                file.isFile && managedTopLevelPrefixes.any { prefix -> file.name.startsWith(prefix) }
            }
            ?.forEach { file -> total = addBytesSaturated(total, file.length().coerceAtLeast(0L)) }

        filesDir?.let { root ->
            managedFilesDirectories.forEach { name ->
                total = addDirectoryBytesSaturated(total, File(root, name))
            }
        }

        val durableDubbing = noBackupFilesDir?.let { File(it, "dub") }
        if (durableDubbing != null && !sameDirectory(durableDubbing, File(cacheDir, "dub"))) {
            total = addDirectoryBytesSaturated(total, durableDubbing)
        }
        val durableAudio = noBackupFilesDir?.let { File(it, "asr_audio_retry") }
        if (durableAudio != null && !sameDirectory(durableAudio, File(cacheDir, "asr_audio_retry"))) {
            total = addDirectoryBytesSaturated(total, durableAudio)
        }
        return total
    }

    /** Removes processing outputs and restore caches, but preserves Room, history, models and settings. */
    fun clear(
        cacheDir: File,
        noBackupFilesDir: File? = null,
        filesDir: File? = null,
    ): Boolean {
        var succeeded = true
        managedCacheDirectories.forEach { name ->
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

        filesDir?.let { root ->
            managedFilesDirectories.forEach { name ->
                val directory = File(root, name)
                if (directory.exists() && !directory.deleteRecursively() && directory.exists()) {
                    succeeded = false
                }
            }
        }

        val durableDubbing = noBackupFilesDir?.let { File(it, "dub") }
        if (durableDubbing != null && !sameDirectory(durableDubbing, File(cacheDir, "dub")) &&
            durableDubbing.exists() && !durableDubbing.deleteRecursively() && durableDubbing.exists()
        ) {
            succeeded = false
        }
        val durableAudio = noBackupFilesDir?.let { File(it, "asr_audio_retry") }
        if (durableAudio != null && !sameDirectory(durableAudio, File(cacheDir, "asr_audio_retry")) &&
            durableAudio.exists() && !durableAudio.deleteRecursively() && durableAudio.exists()
        ) {
            succeeded = false
        }
        return succeeded
    }

    private fun addDirectoryBytesSaturated(initial: Long, directory: File): Long {
        var total = initial
        if (directory.isDirectory) {
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                total = addBytesSaturated(total, file.length().coerceAtLeast(0L))
            }
        }
        return total
    }

    private fun sameDirectory(first: File, second: File): Boolean =
        runCatching { first.canonicalFile == second.canonicalFile }
            .getOrDefault(first.absoluteFile == second.absoluteFile)
}
