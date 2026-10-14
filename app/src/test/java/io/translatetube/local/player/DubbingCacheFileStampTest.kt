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
}
