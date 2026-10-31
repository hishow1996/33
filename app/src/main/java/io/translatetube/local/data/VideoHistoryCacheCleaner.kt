package io.translatetube.local.data

import android.content.Context
import io.translatetube.local.player.DubbingCachePath
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

        val audioDirectory = File(context.cacheDir, "asr_audio_retry")
        val audioNames = VideoHistoryCacheIndex.audioCacheFileNames(context, video.key).toMutableSet()
        val youtubeId = video.videoId
            ?: video.key.takeIf { it.startsWith("youtube:") }?.removePrefix("youtube:")
        youtubeId?.takeIf { it.isNotBlank() }?.let {
            audioNames += VideoHistoryCacheIndex.audioCacheFileNameForIdentity(it)
        }
        for (name in audioNames) {
            if (!Regex("^[0-9a-f]{32}\\.bin$").matches(name)) continue
            val audioFile = File(audioDirectory, name)
            deleteFileIfPresent(audioFile, "转写音轨")
            deleteFileIfPresent(File(audioDirectory, "$name.part"), "转写音轨下载临时文件")
            val segmentDirectory = File(audioDirectory, "${name.removeSuffix(".bin")}.segments")
            deleteDirectoryIfPresent(segmentDirectory, "HLS 音轨分段")
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
        database.subtitleCacheDao().clear()
        val count = database.videoDao().deleteAll()
        PlaybackService.clearAllHistorySessionConfig(context)
        VideoHistoryCacheIndex.clearAll(context)
        return count
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
