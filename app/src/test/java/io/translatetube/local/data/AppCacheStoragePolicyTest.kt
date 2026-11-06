package io.translatetube.local.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCacheStoragePolicyTest {
    @Test
    fun countsCurrentAsrRetryAndHlsSegmentDirectories() {
        val root = createTempDir(prefix = "app-cache-size")
        try {
            File(root, "dub/video/00001.pcm").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(17))
            }
            File(root, "tts_tmp/tts_1.wav").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(23))
            }
            File(root, "asr_audio_retry/abc.segments/00001.seg").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(31))
            }
            File(root, "translate-pcm-leftover.s16le").writeBytes(ByteArray(13))
            File(root, "asr_audio_legacy.bin").writeBytes(ByteArray(19))
            File(root, "piper-model-zh_cn_chaowen_medium.tar.bz2.tmp").writeBytes(ByteArray(29))
            File(root, "unrelated.bin").writeBytes(ByteArray(101))

            assertEquals(17L + 23L + 31L + 13L + 19L + 29L, AppCacheStoragePolicy.sizeBytes(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearsManagedCacheButPreservesUnrelatedFiles() {
        val root = createTempDir(prefix = "app-cache-clear")
        try {
            File(root, "asr_audio_retry/abc.segments/00001.seg").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(32))
            }
            File(root, "dub/cue.pcm").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(16))
            }
            File(root, "piper-model-zh_cn_chaowen_medium.tar.bz2.tmp").writeBytes(ByteArray(29))
            val keep = File(root, "unrelated.bin").apply { writeText("keep") }

            assertTrue(AppCacheStoragePolicy.clear(root))
            assertFalse(File(root, "asr_audio_retry").exists())
            assertFalse(File(root, "dub").exists())
            assertFalse(File(root, "piper-model-zh_cn_chaowen_medium.tar.bz2.tmp").exists())
            assertTrue(keep.isFile)
            assertEquals(0L, AppCacheStoragePolicy.sizeBytes(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun includesDurableAsrAudioCacheInSizeAndClearOperations() {
        val cacheRoot = createTempDir(prefix = "app-cache-durable")
        val noBackupRoot = createTempDir(prefix = "app-no-backup")
        try {
            File(cacheRoot, "dub/cue.pcm").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(13))
            }
            File(noBackupRoot, "asr_audio_retry/video.bin").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(37))
            }
            File(noBackupRoot, "dub/video/cue.pcm").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(23))
            }
            File(noBackupRoot, "unrelated.keep").writeText("keep")

            assertEquals(73L, AppCacheStoragePolicy.sizeBytes(cacheRoot, noBackupRoot))
            assertTrue(AppCacheStoragePolicy.clear(cacheRoot, noBackupRoot))
            assertFalse(File(noBackupRoot, "asr_audio_retry").exists())
            assertFalse(File(noBackupRoot, "dub").exists())
            assertTrue(File(noBackupRoot, "unrelated.keep").isFile)
            assertEquals(0L, AppCacheStoragePolicy.sizeBytes(cacheRoot, noBackupRoot))
        } finally {
            cacheRoot.deleteRecursively()
            noBackupRoot.deleteRecursively()
        }
    }

    @Test
    fun includesAndClearsPersistentProcessingRecoveryDirectories() {
        val cacheRoot = createTempDir(prefix = "app-cache-recovery")
        val filesRoot = createTempDir(prefix = "app-files-recovery")
        try {
            File(cacheRoot, "local_asr_pcm16/source.pcm16").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(19))
            }
            File(filesRoot, "in_progress_processing/progress_old.json").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(23))
            }
            File(filesRoot, "ready_session_snapshots/ready_old.json").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(29))
            }
            File(filesRoot, "app_logs/keep.log").apply {
                parentFile?.mkdirs()
                writeText("keep")
            }

            assertEquals(
                19L + 23L + 29L,
                AppCacheStoragePolicy.sizeBytes(cacheRoot, filesDir = filesRoot),
            )
            assertTrue(AppCacheStoragePolicy.clear(cacheRoot, filesDir = filesRoot))
            assertFalse(File(cacheRoot, "local_asr_pcm16").exists())
            assertFalse(File(filesRoot, "in_progress_processing").exists())
            assertFalse(File(filesRoot, "ready_session_snapshots").exists())
            assertTrue(File(filesRoot, "app_logs/keep.log").isFile)
            assertEquals(0L, AppCacheStoragePolicy.sizeBytes(cacheRoot, filesDir = filesRoot))
        } finally {
            cacheRoot.deleteRecursively()
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun includesAndClearsScopedAndLegacyCloudAsrCheckpoints() {
        val root = createTempDir(prefix = "app-cache-cloud-checkpoints")
        try {
            File(root, "cloud_asr_response_checkpoints/cloud-asr-elevenlabs-legacy.bin").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(7))
            }
            File(root, "kawa_asr_checkpoints/kawa-legacy.json").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(11))
            }
            val scoped = File(root, "history_asr_${"a".repeat(32)}")
            File(scoped, "cloud_asr_response_checkpoints/cloud-asr-cartesia-current.bin").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(13))
            }
            File(scoped, "kawa_asr_checkpoints/kawa-current.json").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(17))
            }
            val keep = File(root, "history_asr_not-a-hash/keep.bin").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(19))
            }

            assertEquals(7L + 11L + 13L + 17L, AppCacheStoragePolicy.sizeBytes(root))
            assertTrue(AppCacheStoragePolicy.clear(root))
            assertFalse(File(root, "cloud_asr_response_checkpoints").exists())
            assertFalse(File(root, "kawa_asr_checkpoints").exists())
            assertFalse(scoped.exists())
            assertTrue(keep.isFile)
            assertEquals(19L, AppCacheStoragePolicy.sizeBytes(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesCacheClearWhenPlaybackOrBackgroundWorkIsActive() {
        assertFalse(AppCacheStoragePolicy.canClear(isPlaying = true, isProcessing = false, dubbingIncomplete = false))
        assertFalse(AppCacheStoragePolicy.canClear(isPlaying = false, isProcessing = true, dubbingIncomplete = false))
        assertFalse(AppCacheStoragePolicy.canClear(isPlaying = false, isProcessing = false, dubbingIncomplete = true))
        assertFalse(AppCacheStoragePolicy.canClear(
            isPlaying = false, isProcessing = false, dubbingIncomplete = false, modelDownloadActive = true
        ))
        assertTrue(AppCacheStoragePolicy.canClear(isPlaying = false, isProcessing = false, dubbingIncomplete = false))
    }

    @Test
    fun cacheSizeAdditionSaturatesInsteadOfWrappingNegative() {
        assertEquals(Long.MAX_VALUE, AppCacheStoragePolicy.addBytesSaturated(Long.MAX_VALUE - 4L, 10L))
        assertEquals(Long.MAX_VALUE - 4L, AppCacheStoragePolicy.addBytesSaturated(Long.MAX_VALUE - 4L, 0L))
        assertEquals(12L, AppCacheStoragePolicy.addBytesSaturated(10L, 2L))
    }

}
