package io.translatetube.local.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioCacheStorageTest {
    @Test
    fun migratesLegacyAudioAndHlsSegmentsWithoutChangingNames() {
        val root = createTempDir(prefix = "asr-audio-migration")
        try {
            val legacy = File(root, "cache/asr_audio_retry")
            val destination = File(root, "no-backup/asr_audio_retry")
            File(legacy, "0123456789abcdef0123456789abcdef.bin").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(64))
            }
            File(legacy, "0123456789abcdef0123456789abcdef.segments/00000.seg").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(32))
            }
            File(legacy, "fedcba9876543210fedcba9876543210.bin.part").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(8))
            }

            assertTrue(AsrAudioCacheStorage.migrateLegacyDirectory(legacy, destination))
            assertFalse(legacy.exists())
            assertEquals(64L, File(destination, "0123456789abcdef0123456789abcdef.bin").length())
            assertEquals(
                32L,
                File(destination, "0123456789abcdef0123456789abcdef.segments/00000.seg").length(),
            )
            assertEquals(
                8L,
                File(destination, "fedcba9876543210fedcba9876543210.bin.part").length(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergesExistingPersistentCacheAndKeepsItsNonEmptyFiles() {
        val root = createTempDir(prefix = "asr-audio-merge")
        try {
            val legacy = File(root, "cache/asr_audio_retry")
            val destination = File(root, "no-backup/asr_audio_retry")
            File(destination, "same.bin").apply {
                parentFile?.mkdirs()
                writeText("persistent")
            }
            File(legacy, "same.bin").apply {
                parentFile?.mkdirs()
                writeText("legacy")
            }
            File(legacy, "new.bin").writeText("new-audio")

            assertTrue(AsrAudioCacheStorage.migrateLegacyDirectory(legacy, destination))
            assertFalse(legacy.exists())
            assertEquals("persistent", File(destination, "same.bin").readText())
            assertEquals("new-audio", File(destination, "new.bin").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
