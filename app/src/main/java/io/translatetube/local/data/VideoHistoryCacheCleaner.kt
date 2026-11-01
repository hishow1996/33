package io.translatetube.local.data

import android.content.Context
import io.translatetube.local.player.DubbingCachePath
import io.translatetube.local.player.HlsAudioDownloader
import io.translatetube.local.player.PlaybackService
import java.io.File
import java.io.IOException

/** Deletes a history row only after its video-specific caches have been removed successfully. */
internal object VideoHistoryCacheCleaner {
    suspend fun deleteVideo(context: Context, database: AppDatabase, video: VideoEntity) {
        val dubbingDirectory = File(
            File(context.cacheDir, "dub"),
            DubbingCachePath.directoryName(video.key),
        )
        deleteDirectoryIfPresent(dubbingDirectory, "配音 PCM")

        val audioDirectory = File(context.noBackupFilesDir, "asr_audio_retry")
        val audioNames = VideoHistoryCacheIndex.audioCacheFileNames(context, video.key).toMutableSet()
        val youtubeId = video.videoId
            ?: video.key.takeIf { it.startsWith("youtube:") }?.removePrefix("youtube:")
        youtubeId?.takeIf { it.isNotBlank() }?.let {
            audioNames += VideoHistoryCacheIndex.audioCacheFileNameForIdentity(it)
        }
        for (name in audioNames) {
            if (!Regex("^[0-9a-f]{32}\\.bin$").matches(name)) continue
            val audioFile = File(audioDirectory, name)
            if (!PlaybackService.deleteUnleasedAsrAudioCacheFile(audioFile)) {
                throw IOException("转写音轨缓存正在使用，暂时无法删除：$name")
            }
            deleteFileIfPresent(File(audioDirectory, "$name.part"), "转写音轨下载临时文件")
            if (!HlsAudioDownloader.cleanupSegmentCache(audioFile)) {
                throw IOException("HLS 音轨分段缓存正在使用，暂时无法删除：$name")
            }
        }

        // Decoded local PCM is indexed separately from downloaded .bin files because it has a
        // different format and storage budget. Refuse to remove it while an ASR reader holds it.
        val localPcmDirectory = File(context.cacheDir, "local_asr_pcm16")
        for (name in VideoHistoryCacheIndex.localPcmCacheFileNames(context, video.key)) {
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

        database.subtitleCacheDao().deleteForVideo(video.key, "${video.key}|")
        // Remove the row last: failures above keep it visible so the user can retry the cleanup.
        database.videoDao().delete(video.key)
        PlaybackService.clearHistorySessionConfig(context, video.key)
        VideoHistoryCacheIndex.remove(context, video.key)
    }

    suspend fun deleteAll(context: Context, database: AppDatabase): Int {
        if (!AppCacheStoragePolicy.clear(context.cacheDir)) {
            throw IOException("部分应用缓存仍无法删除；历史记录保留，请停止相关任务后重试。")
        }
        clearDurableAsrAudioCache(context)
        database.subtitleCacheDao().clear()
        val count = database.videoDao().deleteAll()
        PlaybackService.clearAllHistorySessionConfig(context)
        VideoHistoryCacheIndex.clearAll(context)
        return count
    }

    /**
     * Source audio now lives outside cacheDir so Android does not evict it opportunistically.
     * History-wide deletion still removes it, but active ASR readers/downloaders keep their files.
     */
    private suspend fun clearDurableAsrAudioCache(context: Context) {
        val directory = File(context.noBackupFilesDir, "asr_audio_retry")
        val entries = directory.listFiles()?.toList().orEmpty()
        for (entry in entries) {
            val owner = when {
                entry.name.endsWith(".bin.part") -> File(directory, entry.name.removeSuffix(".part"))
                entry.name.endsWith(".segments") -> File(directory, entry.name.removeSuffix(".segments") + ".bin")
                entry.name.endsWith(".bin") -> entry
                else -> null
            }
            if (owner != null && !PlaybackService.deleteUnleasedAsrAudioCacheFile(owner)) {
                throw IOException("转写音轨缓存正在使用，暂时无法清理：${owner.name}")
            }
            when {
                entry.name.endsWith(".segments") -> {
                    if (!HlsAudioDownloader.cleanupSegmentCache(requireNotNull(owner))) {
                        throw IOException("HLS 音轨分段仍在使用，暂时无法清理：${entry.name}")
                    }
                }
                entry.name.endsWith(".bin.part") -> deleteFileIfPresent(entry, "转写音轨下载临时文件")
                entry.name.endsWith(".bin") -> deleteFileIfPresent(entry, "转写音轨")
                else -> deleteDirectoryIfPresent(entry, "转写音轨临时缓存")
            }
        }
        if (directory.exists() && !directory.deleteRecursively() && directory.exists()) {
            throw IOException("持久转写音轨缓存目录仍被占用，历史记录保留。")
        }
    }

    private fun deleteFileIfPresent(file: File, label: String) {
        if (file.exists() && !file.delete() && file.exists()) {
            throw IOException("$label 删除失败：${file.name}")
        }
    }

    private fun deleteDirectoryIfPresent(directory: File, label: String) {
        if (directory.exists() && !directory.deleteRecursively() && directory.exists()) {
            throw IOException("$label 目录删除失败")
        }
    }
}
