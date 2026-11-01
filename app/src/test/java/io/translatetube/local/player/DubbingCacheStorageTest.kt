package io.translatetube.local.player

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCacheStorageTest {
    @Test
    fun migratesFinalPcmFilesAndPreservesTheirCacheIdentity() {
        val root = createTempDir(prefix = "dubbing-cache-migration")
        try {
            val legacy = File(root, "cache/dub/video").apply { mkdirs() }
            val durable = File(root, "no-backup/dub/video")
            val bytes = ByteArray(1024) { (it % 127).toByte() }
            val source = File(legacy, "cue-v11.pcm").apply { writeBytes(bytes) }
            val oldTimestamp = 1_700_000_000_000L
            source.setLastModified(oldTimestamp)
            File(legacy, "unfinished.pcm.tmp").writeText("partial")

            val count = DubbingCacheStorage.migrateLegacyVideoDirectory(legacy, durable)

            assertEquals(1, count)
            assertArrayEquals(bytes, File(durable, source.name).readBytes())
            assertEquals(oldTimestamp, File(durable, source.name).lastModified())
            assertFalse(source.exists())
            assertTrue(File(legacy, "unfinished.pcm.tmp").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsLegacySourceWhenDurableFileWithSameIdentityHasDifferentLength() {
        val root = createTempDir(prefix = "dubbing-cache-conflict")
        try {
            val legacy = File(root, "cache/dub/video").apply { mkdirs() }
            val durable = File(root, "no-backup/dub/video").apply { mkdirs() }
            val source = File(legacy, "cue.pcm").apply { writeBytes(ByteArray(64) { 1 }) }
            val existing = File(durable, "cue.pcm").apply { writeBytes(ByteArray(32) { 2 }) }

            val count = DubbingCacheStorage.migrateLegacyVideoDirectory(legacy, durable)

            assertEquals(0, count)
            assertTrue(source.isFile)
            assertArrayEquals(ByteArray(32) { 2 }, existing.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun leavesLegacySourceUntouchedWhenDestinationCannotBeCreated() {
        val root = createTempDir(prefix = "dubbing-cache-unwritable")
        try {
            val legacy = File(root, "cache/dub/video").apply { mkdirs() }
            val source = File(legacy, "cue.pcm").apply { writeBytes(ByteArray(16) { 3 }) }
            val durableParentFile = File(root, "not-a-directory").apply { writeText("block") }
            val durable = File(durableParentFile, "video")

            val count = DubbingCacheStorage.migrateLegacyVideoDirectory(legacy, durable)

            assertEquals(0, count)
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
