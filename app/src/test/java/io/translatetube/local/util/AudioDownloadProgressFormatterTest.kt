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
