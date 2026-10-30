package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusMtProgressDisplayPolicyTest {
    @Test
    fun usesConfiguredTotalBeforeFirstStateRefresh() {
        val display = OpusMtProgressDisplayPolicy.resolve(
            state = OpusMtDownloadState(fileCount = 5, totalBytes = -1L),
            configuredTotalBytes = 1_000L,
        )

        assertEquals(0L, display.downloadedBytes)
        assertEquals(1_000L, display.totalBytes)
        assertEquals(0, display.progress)
        assertFalse(display.visible)
    }

    @Test
    fun readyModelAlwaysDisplaysFullBytesAndFullProgress() {
        val display = OpusMtProgressDisplayPolicy.resolve(
            state = OpusMtDownloadState(
                status = OpusMtStatus.READY,
                progress = 0,
                downloadedBytes = 0L,
                totalBytes = -1L,
            ),
            configuredTotalBytes = 1_000L,
        )

        assertEquals(1_000L, display.downloadedBytes)
        assertEquals(1_000L, display.totalBytes)
        assertEquals(100, display.progress)
        assertTrue(display.visible)
    }

    @Test
    fun completeFilesRemainFullDuringRuntimeValidationFailure() {
        val display = OpusMtProgressDisplayPolicy.resolve(
            state = OpusMtDownloadState(
                status = OpusMtStatus.FAILED,
                progress = 100,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                error = "运行时验证失败",
            ),
            configuredTotalBytes = 1_000L,
        )

        assertEquals(1_000L, display.downloadedBytes)
        assertEquals(1_000L, display.totalBytes)
        assertEquals(100, display.progress)
        assertTrue(display.visible)
    }

    @Test
    fun partialDownloadKeepsRealByteCountAndClampsProgress() {
        val display = OpusMtProgressDisplayPolicy.resolve(
            state = OpusMtDownloadState(
                status = OpusMtStatus.PAUSED,
                progress = 140,
                downloadedBytes = 700L,
                totalBytes = 1_000L,
            ),
            configuredTotalBytes = 1_000L,
        )

        assertEquals(700L, display.downloadedBytes)
        assertEquals(1_000L, display.totalBytes)
        assertEquals(100, display.progress)
        assertTrue(display.visible)
    }

    @Test
    fun notDownloadedModelDoesNotShowAnEmptyProgressRow() {
        val display = OpusMtProgressDisplayPolicy.resolve(
            state = OpusMtDownloadState(
                status = OpusMtStatus.NOT_DOWNLOADED,
                downloadedBytes = 0L,
                totalBytes = -1L,
            ),
            configuredTotalBytes = 1_000L,
        )

        assertEquals(0L, display.downloadedBytes)
        assertEquals(1_000L, display.totalBytes)
        assertEquals(0, display.progress)
        assertFalse(display.visible)
    }
}
