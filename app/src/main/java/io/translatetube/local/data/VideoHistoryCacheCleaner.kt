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

/** Deletes a history row only after all task-owned files, checkpoints and indexes are gone. */
internal object VideoHistoryCacheCleaner {
    suspend fun deleteVideo(context: Context, database: AppDatabase, video: VideoEntity) {
        // First retire the in-memory READY session so it cannot recreate files during cleanup.
        PlaybackService.invalidateDeletedHistorySession(video.key)

        // Capture indexed artifacts before advancing the generation; keep the index intact if a
        // later delete fails so the visible history row remains retryable.
        val audioNames = VideoHistoryCacheIndex.audioCacheFileNames(context, video.key).toMutableSet()
        val localPcmNames = VideoHistoryCacheIndex.localPcmCacheFileNames(context, video.key)
        if (!VideoHistoryCacheIndex.advanceGenerationForDeletion(context, video.key)) {
            throw IOException("缓存代次更新失败，历史记录保留，请重试。")
        }

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

        // Decoded PCM has its own index and can be stored under cacheDir.
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
    }

    /**
     * Clear every generated processing artifact while keeping history rows, original user media,
     * installed models, API credentials and app settings. Both storage cleanup actions and
     * delete-all-history use this exact path so their cleanup coverage cannot drift.
     */
    suspend fun clearAllProcessingData(context: Context, database: AppDatabase) {
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
    }

    suspend fun deleteAll(context: Context, database: AppDatabase): Int {
        clearAllProcessingData(context, database)
        return database.videoDao().deleteAll()
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
