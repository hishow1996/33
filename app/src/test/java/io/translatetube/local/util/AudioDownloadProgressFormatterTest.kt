package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownloadProgressFormatterTest {
    @Test
    fun formatsByteUnitsAsReadableSizes() {
        assertEquals("0 B", AudioDownloadProgressFormatter.formatBytes(0L))
        assertEquals("1.0 KB", AudioDownloadProgressFormatter.formatBytes(1024L))
        assertEquals("1.0 MB", AudioDownloadProgressFormatter.formatBytes(1024L * 1024L))
    }

    @Test
    fun includesExactTotalAndPercentWhenAvailable() {
        assertEquals(
            "正在下载音轨 1.0 MB / 2.0 MB · 50%",
            AudioDownloadProgressFormatter.formatProgress(
                label = "正在下载音轨",
                downloadedBytes = 1024L * 1024L,
                totalBytes = 2L * 1024L * 1024L,
                percent = 50,
            ),
        )
    }

    @Test
    fun labelsEstimatedTotalAsApproximate() {
        assertEquals(
            "正在提取音轨 1.0 MB / 预计约 4.0 MB · 25%",
            AudioDownloadProgressFormatter.formatProgress(
                label = "正在提取音轨",
                downloadedBytes = 1024L * 1024L,
                totalBytes = 4L * 1024L * 1024L,
                percent = 25,
                totalIsEstimate = true,
            ),
        )
    }

    @Test
    fun estimatesAudioOutputBytesFromBitrateAndDuration() {
        assertEquals(
            1_200_000L,
            AudioDownloadProgressFormatter.estimateBytesFromBitrateAndDuration(
                bitRateBitsPerSecond = 96_000L,
                durationUs = 100_000_000L,
            ),
        )
        assertEquals(
            null,
            AudioDownloadProgressFormatter.estimateBytesFromBitrateAndDuration(
                bitRateBitsPerSecond = null,
                durationUs = 100_000_000L,
            ),
        )
    }

    @Test
    fun estimatesTotalBytesFromObservedTimelineProgress() {
        assertEquals(
            4_000L,
            AudioDownloadProgressFormatter.estimateBytesFromObservedProgress(
                downloadedBytes = 1_000L,
                elapsedDurationUs = 25_000_000L,
                totalDurationUs = 100_000_000L,
            ),
        )
    }

    @Test
    fun doesNotInventUnknownTotal() {
        assertEquals(
            "正在下载音轨 1.0 MB",
            AudioDownloadProgressFormatter.formatProgress(
                label = "正在下载音轨",
                downloadedBytes = 1024L * 1024L,
            ),
        )
    }
}
