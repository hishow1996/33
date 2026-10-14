package io.translatetube.local.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTempFilePolicyTest {
    @Test
    fun cleanupRemovesOnlyAbandonedPcmScratchFiles() {
        val dir = createTempDir(prefix = "pcm-policy-test")
        val now = 10_000_000_000L
        try {
            val stale = File(dir, "translate-pcm-old.s16le").apply {
                writeText("old")
                setLastModified(now - PcmTempFilePolicy.MAX_AGE_MS - 1L)
            }
            val recent = File(dir, "translate-pcm-recent.s16le").apply {
                writeText("recent")
                setLastModified(now - 60_000L)
            }
            val unrelated = File(dir, "download-cache.bin").apply {
                writeText("keep")
                setLastModified(now - PcmTempFilePolicy.MAX_AGE_MS * 3L)
            }

            assertEquals(1, PcmTempFilePolicy.cleanupStale(dir, now))
            assertFalse(stale.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pcmAppendNeverExceedsConfiguredScratchBudget() {
        val budget = 512L * 1024L * 1024L
        assertTrue(PcmTempFilePolicy.canAppendPcm(0L, 64L * 1024L, budget))
        assertTrue(PcmTempFilePolicy.canAppendPcm(budget - 1L, 1L, budget))
        assertFalse(PcmTempFilePolicy.canAppendPcm(budget, 1L, budget))
        assertFalse(PcmTempFilePolicy.canAppendPcm(-1L, 1L, budget))
        assertFalse(PcmTempFilePolicy.canAppendPcm(0L, -1L, budget))
    }

    @Test
    fun pcmWriterKeepsMinimumFreeSpaceReserve() {
        val reserve = PcmTempFilePolicy.MIN_FREE_SPACE_BYTES
        assertTrue(PcmTempFilePolicy.hasDiskHeadroom(-1L, 1024L, reserve))
        assertFalse(PcmTempFilePolicy.hasDiskHeadroom(0L, 0L, reserve))
        assertTrue(PcmTempFilePolicy.hasDiskHeadroom(reserve + 4096L, 4096L, reserve))
        assertFalse(PcmTempFilePolicy.hasDiskHeadroom(reserve + 4095L, 4096L, reserve))
        assertFalse(PcmTempFilePolicy.hasDiskHeadroom(reserve - 1L, 0L, reserve))
    }

    @Test
    fun futureTimestampsAndUnrelatedNamesAreNotTreatedAsStale() {
        val now = 10_000_000_000L
        assertFalse(PcmTempFilePolicy.isStale("translate-pcm-a.s16le", now + 1L, now))
        assertFalse(PcmTempFilePolicy.isStale("other-a.s16le", now - PcmTempFilePolicy.MAX_AGE_MS * 2, now))
    }
}
