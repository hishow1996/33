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
    fun futureTimestampsAndUnrelatedNamesAreNotTreatedAsStale() {
        val now = 10_000_000_000L
        assertFalse(PcmTempFilePolicy.isStale("translate-pcm-a.s16le", now + 1L, now))
        assertFalse(PcmTempFilePolicy.isStale("other-a.s16le", now - PcmTempFilePolicy.MAX_AGE_MS * 2, now))
    }

    @Test
    fun scratchPcmWritesAreBoundedByThe512MiBBudget() {
        val budget = PcmTempFilePolicy.MAX_PCM_BYTES
        assertTrue(PcmTempFilePolicy.canAppendSamples(0L, 1))
        assertTrue(PcmTempFilePolicy.canAppendSamples(budget - 2L, 1))
        assertFalse(PcmTempFilePolicy.canAppendSamples(budget, 1))
        assertFalse(PcmTempFilePolicy.canAppendSamples(budget - 1L, 1))
        assertFalse(PcmTempFilePolicy.canAppendSamples(-1L, 1))
        assertFalse(PcmTempFilePolicy.canAppendSamples(0L, -1))
    }
}
