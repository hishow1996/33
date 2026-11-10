package io.translatetube.local.data

import android.content.Context
import io.translatetube.local.player.AsrAudioCacheStorage
import io.translatetube.local.player.DubbingCacheStorage
import io.translatetube.local.player.HlsAudioDownloader
import io.translatetube.local.player.InProgressProcessingCheckpointStore
import io.translatetube.local.player.PendingProcessingResumeStore
import io.translatetube.local.player.PlaybackService
import io.translatetube.local.player.SpeakerRecognitionTaskJournal
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deletes a history row only after all task-owned files, checkpoints and indexes are gone.
 * Durable tombstones make every destructive operation retryable after process death.
 */
internal object VideoHistoryCacheCleaner {
    private val cleanupMutex = Mutex()

    suspend fun deleteVideo(context: Context, database: AppDatabase, video: VideoEntity) =
        cleanupMutex.withLock {
            deleteVideoLocked(context, database, video)
        }

    private suspend fun deleteVideoLocked(context: Context, database: AppDatabase, video: VideoEntity) {
        if (!PendingVideoHistoryDeletionStore.markVideoPending(context, video.key)) {
            throw IOException("删除任务标记写入失败，历史记录未删除，请重试。")
        }

        // First retire the in-memory READY session so it cannot recreate files during cleanup.
        PlaybackService.invalidateDeletedHistorySessionAndAwait(video.key)

        // Capture indexed artifacts before advancing the generation; keep the index intact if a
        // later delete fails so the visible history row remains retryable.
        val audioNames = VideoHistoryCacheIndex.audioCacheFileNames(context, video.key).toMutableSet()
        val localPcmNames = VideoHistoryCacheIndex.localPcmCacheFileNames(context, video.key)
        if (!VideoHistoryCacheIndex.advanceGenerationForDeletion(context, video.key)) {
            throw IOException("缓存代次更新失败，历史记录保留，请重试。")
        }

        // Provider checkpoints are isolated by history key and deletion generation.
        deleteDirectoryIfPresent(
            File(context.cacheDir, VideoHistoryCacheIndex.asrCheckpointDirectoryName(video.key)),
            "云端转写检查点",
        )

        val dubbingDirectories = listOf(
            DubbingCacheStorage.videoDirectory(context, video.key),
            DubbingCacheStorage.legacyVideoDirectory(context, video.key),
        )
        dubbingDirectories.forEach { directory ->
            deleteDirectoryIfPresent(directory, "配音 PCM")
        }

        val audioDirectory = AsrAudioCacheStorage.directory(context)
        val youtubeId = video.videoId
            ?: video.key.takeIf { it.startsWith("youtube:") }?.removePrefix("youtube:")
        youtubeId?.takeIf { it.isNotBlank() }?.let {
            // Also remove the pre-generation cache name written by older app versions.
            audioNames += VideoHistoryCacheIndex.audioCacheFileNameForIdentity(it)
        }
        for (name in audioNames) {
            if (!Regex("^[0-9a-f]{32}\\.bin$").matches(name)) continue
            if (VideoHistoryCacheIndex.isAudioCacheFileReferencedByAnotherHistory(context, video.key, name)) {
                continue
            }
            val audioFile = File(audioDirectory, name)
            if (!PlaybackService.deleteUnleasedAsrAudioCacheFile(audioFile)) {
                throw IOException("转写音轨缓存正在使用，暂时无法删除：$name")
            }
            deleteFileIfPresent(File(audioDirectory, "$name.part"), "转写音轨下载临时文件")
            if (!HlsAudioDownloader.cleanupSegmentCache(audioFile)) {
                throw IOException("HLS 音轨分段缓存正在使用，暂时无法删除：$name")
            }
        }

        val localPcmDirectory = File(context.cacheDir, "local_asr_pcm16")
        for (name in localPcmNames) {
            if (!Regex("^[0-9a-f]{32}\\.pcm16$").matches(name)) continue
            if (
                VideoHistoryCacheIndex.isLocalPcmCacheFileReferencedByAnotherHistory(
                    context, video.key, name,
                )
            ) continue
            val pcmFile = File(localPcmDirectory, name)
            if (!PlaybackService.deleteUnleasedLocalPcmCacheFile(pcmFile)) {
                throw IOException("转写 PCM 缓存正在使用，暂时无法删除：$name")
            }
            deleteFileIfPresent(File(localPcmDirectory, "$name.part"), "转写 PCM 临时文件")
        }

        if (!InProgressProcessingCheckpointStore.deleteForVideo(context, video.key)) {
            throw IOException("处理中间检查点仍有残留，历史记录保留，请重试。")
        }
        if (!PendingProcessingResumeStore.clearForVideo(context, video.key)) {
            throw IOException("首页任务恢复标记删除失败，历史记录保留，请重试。")
        }
        if (!SpeakerRecognitionTaskJournal.clearForVideo(context, video.key)) {
            throw IOException("说话人识别任务记录删除失败，历史记录保留，请重试。")
        }
        if (!PlaybackService.clearHistorySessionConfig(context, video.key)) {
            throw IOException("已完成处理结果或恢复配置仍有残留，历史记录保留，请重试。")
        }

        database.subtitleCacheDao().deleteForVideo(video.key, "${video.key}|")
        deleteEmptyDirectoryIfPresent(File(context.noBackupFilesDir, "dub"), "持久配音缓存")
        deleteEmptyDirectoryIfPresent(File(context.cacheDir, "dub"), "旧版配音缓存")
        deleteEmptyDirectoryIfPresent(localPcmDirectory, "本地转写 PCM 缓存")
        deleteEmptyDirectoryIfPresent(audioDirectory, "转写音轨缓存")
        if (!VideoHistoryCacheIndex.removeForVideo(context, video.key)) {
            throw IOException("缓存索引删除失败，历史记录保留，请重试。")
        }

        // Remove the history row last so any failed cleanup remains visible and retryable.
        database.videoDao().delete(video.key)
        if (!PendingVideoHistoryDeletionStore.clearVideoPending(context, video.key)) {
            // The row is already gone and all task files are removed. Startup recovery recognizes
            // the missing row and safely drops this final tombstone.
            throw IOException("删除完成标记写入失败，应用下次启动将自动核对清理状态。")
        }
    }

    /** Retry interrupted single-item, cache-only and delete-all operations on app startup. */
    suspend fun recoverPendingDeletions(context: Context, database: AppDatabase): Int {
        if (PendingVideoHistoryDeletionStore.isDeleteAllHistoryPending(context)) {
            deleteAll(context, database)
            return 1
        }

        var recovered = 0
        if (PendingVideoHistoryDeletionStore.isProcessingClearPending(context)) {
            clearAllProcessingData(context, database)
            recovered++
        }

        val pendingHashes = PendingVideoHistoryDeletionStore.pendingVideoHashes(context)
        if (pendingHashes.isEmpty()) return recovered

        val videosByHash = database.videoDao().allForCleanup()
            .associateBy { PendingVideoHistoryDeletionStore.videoKeyHash(it.key) }
        val failures = mutableListOf<Exception>()
        for (hash in pendingHashes) {
            val video = videosByHash[hash]
            if (video == null) {
                // The history row is deleted only after every artifact has been removed.
                if (PendingVideoHistoryDeletionStore.clearVideoPendingHash(context, hash)) {
                    recovered++
                } else {
                    failures += IOException("已删除历史记录的最终清理标记仍无法移除。")
                }
                continue
            }
            try {
                deleteVideo(context, database, video)
                recovered++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) {
            throw IOException(
                "启动时仍有 ${failures.size} 条视频删除任务未完成；已保留清理标记，后续启动会继续重试。",
                failures.first(),
            )
        }
        return recovered
    }

    /**
     * Clear every generated processing artifact while keeping history rows, original user media,
     * installed models, API credentials and app settings.
     */
    suspend fun clearAllProcessingData(context: Context, database: AppDatabase) {
        if (!PendingVideoHistoryDeletionStore.markProcessingClearPending(context)) {
            throw IOException("全量缓存清理标记写入失败，尚未开始清理。")
        }
        PlaybackService.invalidateAllDeletedHistorySessions()
        if (!AppCacheStoragePolicy.clear(context.cacheDir, context.noBackupFilesDir, context.filesDir)) {
            throw IOException("部分处理缓存仍无法删除；请停止相关任务后重试。")
        }
        database.subtitleCacheDao().clear()
        if (!InProgressProcessingCheckpointStore.clearAll(context)) {
            throw IOException("处理中间检查点仍有残留，请重试。")
        }
        if (!PlaybackService.clearAllHistorySessionConfig(context)) {
            throw IOException("已完成处理结果或恢复配置仍有残留，请重试。")
        }
        if (!PendingProcessingResumeStore.clearAll(context)) {
            throw IOException("首页任务恢复记录清理失败，请重试。")
        }
        if (!SpeakerRecognitionTaskJournal.clearAll(context)) {
            throw IOException("说话人识别任务记录清理失败，请重试。")
        }
        if (!VideoHistoryCacheIndex.clearAll(context)) {
            throw IOException("缓存索引清理失败，请重试。")
        }
        if (!PendingVideoHistoryDeletionStore.clearProcessingClearPending(context)) {
            throw IOException("缓存已清理，但无法确认清理完成；应用下次启动将再次核对。")
        }
    }

    suspend fun deleteAll(context: Context, database: AppDatabase): Int = cleanupMutex.withLock {
        if (!PendingVideoHistoryDeletionStore.markDeleteAllHistoryPending(context)) {
            throw IOException("批量删除标记写入失败，历史记录未删除。")
        }

        try {
            // Stop only the current media session, including parsing/transcription/translation/dubbing
            // and playback. Model downloads use separate directories and are intentionally untouched.
            PlaybackService.invalidateAllDeletedHistorySessionsAndAwait()

            val videos = database.videoDao().allForCleanup()
            var removed = 0
            val failures = mutableListOf<String>()
            for (video in videos) {
                try {
                    deleteVideoLocked(context, database, video)
                    removed++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures += "${video.title.ifBlank { video.key }}：${failure.message ?: failure.javaClass.simpleName}"
                }
            }

            if (failures.isNotEmpty()) {
                val summary = "已清理 $removed 条历史记录；${failures.size} 条未完成，原记录已保留以便重试。" +
                    failures.take(3).joinToString(separator = "；", prefix = " ")
                if (!PendingVideoHistoryDeletionStore.clearDeleteAllHistoryPending(context)) {
                    throw IOException("$summary 批量清理保护标记无法解除，应用下次启动会继续重试。")
                }
                throw IOException(summary)
            }

            if (!PendingVideoHistoryDeletionStore.completeDeleteAll(context)) {
                throw IOException("历史记录已清理，但最终清理标记无法移除；应用下次启动将自动核对。")
            }
            removed
        } catch (cancelled: CancellationException) {
            // A screen/lifecycle cancellation must not leave every future playback globally blocked.
            // Per-video tombstones remain durable for any item whose cleanup had already started.
            PendingVideoHistoryDeletionStore.clearDeleteAllHistoryPending(context)
            throw cancelled
        } catch (failure: Exception) {
            PendingVideoHistoryDeletionStore.clearDeleteAllHistoryPending(context)
            throw failure
        }
    }

    private fun deleteFileIfPresent(file: File, label: String) {
        if (file.exists() && !file.delete() && file.exists()) {
            throw IOException("$label 删除失败：${file.name}")
        }
    }

    private fun deleteEmptyDirectoryIfPresent(directory: File, label: String) {
        if (!directory.exists()) return
        val entries = directory.listFiles() ?: throw IOException("$label 目录无法读取")
        if (entries.isEmpty() && !directory.delete() && directory.exists()) {
            throw IOException("$label 空目录删除失败")
        }
    }

    private fun deleteDirectoryIfPresent(directory: File, label: String) {
        if (directory.exists() && !directory.deleteRecursively() && directory.exists()) {
            throw IOException("$label 目录删除失败")
        }
    }
}
