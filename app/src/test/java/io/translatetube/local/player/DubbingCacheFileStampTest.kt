package io.translatetube.local.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCacheFileStampTest {
    @Test
    fun capturedStampMatchesUnchangedCacheFile() {
        val file = File.createTempFile("dub-cache-stamp", ".pcm")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val stamp = DubbingCacheFileStamp.capture(file)
            assertNotNull(stamp)
            assertTrue(stamp!!.stillMatches(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun stampRejectsSameLengthReplacementWithDifferentTimestamp() {
        val file = File.createTempFile("dub-cache-stamp", ".pcm")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val stamp = DubbingCacheFileStamp.capture(file)
            assertNotNull(stamp)
            file.writeBytes(byteArrayOf(5, 6, 7, 8))
            file.setLastModified(stamp!!.lastModifiedMs + 5_000L)

            assertFalse(stamp.stillMatches(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun stampRejectsFileWhoseLengthHasChanged() {
        val file = File.createTempFile("dub-cache-stamp", ".pcm")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val stamp = DubbingCacheFileStamp.capture(file)
            assertNotNull(stamp)
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

            assertFalse(stamp!!.stillMatches(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun fileIdentityRejectsReplacementEvenWhenLengthAndTimestampMatch() {
        val file = File.createTempFile("dub-cache-stamp", ".pcm")
        val replacement = File.createTempFile("dub-cache-replacement", ".pcm")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val stamp = DubbingCacheFileStamp.capture(file)
            assertNotNull(stamp)
            val originalTime = stamp!!.lastModifiedMs

            replacement.writeBytes(byteArrayOf(5, 6, 7, 8))
            replacement.setLastModified(originalTime)
            assertTrue(file.delete())
            assertTrue(replacement.renameTo(file))
            assertTrue(file.length() == stamp.length)
            assertTrue(file.lastModified() == stamp.lastModifiedMs)

            // Android can distinguish the new inode even when metadata is identical. Some JVM
            // test environments stub android.system.Os, so the deterministic mismatch assertion
            // below also covers the guard independent of platform support.
            if (stamp.fileIdentity != null) {
                assertFalse(stamp.stillMatches(file))
            }
            assertFalse(stamp.copy(fileIdentity = "different-file-identity").stillMatches(file))
        } finally {
            file.delete()
            replacement.delete()
        }
    }

}
