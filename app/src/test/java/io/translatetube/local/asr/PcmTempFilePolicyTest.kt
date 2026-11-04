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
    fun completePcmPayloadRequiresAnExistingNonEmptyFileOfDeclaredLength() {
        val dir = createTempDir(prefix = "pcm-payload-test")
        try {
            val complete = File(dir, "complete.s16le").apply {
                writeBytes(byteArrayOf(1, 0, 2, 0))
            }
            val truncated = File(dir, "truncated.s16le").apply {
                writeBytes(byteArrayOf(1, 0))
            }
            val trailing = File(dir, "trailing.s16le").apply {
                writeBytes(byteArrayOf(1, 0, 2, 0, 3, 0))
            }
            val missing = File(dir, "missing.s16le")

            assertTrue(PcmTempFilePolicy.hasCompletePcm16Payload(complete, 2L))
            assertFalse(PcmTempFilePolicy.hasCompletePcm16Payload(truncated, 2L))
            assertFalse(PcmTempFilePolicy.hasCompletePcm16Payload(missing, 1L))
            assertFalse(PcmTempFilePolicy.hasCompletePcm16Payload(trailing, 2L))
            assertFalse(PcmTempFilePolicy.hasCompletePcm16Payload(complete, 0L))
            assertFalse(PcmTempFilePolicy.hasCompletePcm16Payload(complete, Long.MAX_VALUE))
        } finally {
            dir.deleteRecursively()
        }
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
    @Test
    fun rejectsDeclaredPayloadsLargerThanScratchBudgetBeforeCheckingFileLength() {
        val tiny = File.createTempFile("pcm-budget-test", ".s16le")
        try {
            tiny.writeBytes(byteArrayOf(1, 0))
            assertFalse(
                PcmTempFilePolicy.hasCompletePcm16Payload(
                    tiny,
                    PcmTempFilePolicy.MAX_PCM_BYTES / 2L + 1L,
                )
            )
        } finally {
            tiny.delete()
        }
    }

}