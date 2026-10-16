package io.translatetube.local.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoragePreflightPolicyTest {
    @Test
    fun sumsArchiveExtractionAndSafetyMargin() {
        assertEquals(
            350L,
            ModelStoragePreflightPolicy.roundedUpMiB(
                ModelStoragePreflightPolicy.requiredBytes(
                    archiveBytes = 100L * ModelStoragePreflightPolicy.MIB,
                    extractedBytes = 150L * ModelStoragePreflightPolicy.MIB,
                    safetyMarginBytes = 100L * ModelStoragePreflightPolicy.MIB,
                )
            )
        )
    }

    @Test
    fun doesNotBlockWhenAndroidCannotReportFreeSpace() {
        assertTrue(ModelStoragePreflightPolicy.hasEnoughSpace(0L, 500L))
        assertTrue(ModelStoragePreflightPolicy.hasEnoughSpace(-1L, 500L))
        assertFalse(ModelStoragePreflightPolicy.hasEnoughSpace(499L, 500L))
        assertTrue(ModelStoragePreflightPolicy.hasEnoughSpace(500L, 500L))
    }

    @Test
    fun choosesTheLowestKnownFreeSpaceAcrossDownloadAndModelLocations() {
        assertEquals(100L, ModelStoragePreflightPolicy.minimumKnownUsableSpace(200L, 100L))
        assertEquals(25L, ModelStoragePreflightPolicy.minimumKnownUsableSpace(0L, 25L))
        assertEquals(0L, ModelStoragePreflightPolicy.minimumKnownUsableSpace(0L, -1L))
    }

    @Test
    fun saturatesOverflowAndRoundsUpPartialMebibytes() {
        assertEquals(
            Long.MAX_VALUE,
            ModelStoragePreflightPolicy.requiredBytes(Long.MAX_VALUE, 1L, 0L)
        )
        assertEquals(1L, ModelStoragePreflightPolicy.roundedUpMiB(1L))
        assertEquals(2L, ModelStoragePreflightPolicy.roundedUpMiB(ModelStoragePreflightPolicy.MIB + 1L))
    }
}
