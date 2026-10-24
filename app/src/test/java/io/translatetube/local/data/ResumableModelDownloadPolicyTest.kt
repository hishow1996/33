package io.translatetube.local.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableModelDownloadPolicyTest {
    @Test
    fun parsesValidByteRangesAndUnknownTotals() {
        assertEquals(
            ResumableModelDownloadPolicy.ContentRange(1024L, 2047L, 8192L),
            ResumableModelDownloadPolicy.parseContentRange("bytes 1024-2047/8192"),
        )
        assertEquals(
            ResumableModelDownloadPolicy.ContentRange(20L, 39L, null),
            ResumableModelDownloadPolicy.parseContentRange("bytes 20-39/*"),
        )
        assertNull(ResumableModelDownloadPolicy.parseContentRange("bytes 20-19/100"))
        assertNull(ResumableModelDownloadPolicy.parseContentRange("bytes 20-39/30"))
        assertNull(ResumableModelDownloadPolicy.parseContentRange("nonsense"))
    }

    @Test
    fun rejectsRangeResponsesThatWouldAppendTheWrongBytes() {
        val right = ResumableModelDownloadPolicy.parseContentRange("bytes 4096-8191/8192")
        val wrongStart = ResumableModelDownloadPolicy.parseContentRange("bytes 0-4095/8192")
        val changedTotal = ResumableModelDownloadPolicy.parseContentRange("bytes 4096-8191/9000")
        assertTrue(ResumableModelDownloadPolicy.isValidRangeResponse(4096L, right, 8192L))
        assertFalse(ResumableModelDownloadPolicy.isValidRangeResponse(4096L, wrongStart, 8192L))
        assertFalse(ResumableModelDownloadPolicy.isValidRangeResponse(4096L, changedTotal, 8192L))
    }

    @Test
    fun readsTotalFromACompletedRangeRejection() {
        assertEquals(8192L, ResumableModelDownloadPolicy.parseUnsatisfiedRangeTotal("bytes */8192"))
        assertNull(ResumableModelDownloadPolicy.parseUnsatisfiedRangeTotal("bytes */*"))
    }

    @Test
    fun partialFilesAreStablePerSourceAndSeparateAcrossMirrors() {
        val dir = Files.createTempDirectory("model-resume-policy").toFile()
        try {
            val destination = File(dir, "ASR_model.tar.bz2.tmp")
            val first = ResumableModelArchiveDownloader.partFileFor(destination, "https://primary.example/model.tar.bz2")
            val same = ResumableModelArchiveDownloader.partFileFor(destination, "https://primary.example/model.tar.bz2")
            val mirror = ResumableModelArchiveDownloader.partFileFor(destination, "https://mirror.example/model.tar.bz2")
            assertEquals(first, same)
            assertNotEquals(first, mirror)
            assertTrue(ResumableModelArchiveDownloader.orderSourcesByResumeProgress(
                destination,
                listOf("https://primary.example/model.tar.bz2", "https://mirror.example/model.tar.bz2"),
            ).isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
