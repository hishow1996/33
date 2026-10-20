package io.translatetube.local.util

import java.util.Locale

/** Shared, truthful byte/size formatting for audio download UI and notifications. */
internal object AudioDownloadProgressFormatter {
    private val units = arrayOf("KB", "MB", "GB", "TB")

    fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        if (safeBytes < 1024L) return "$safeBytes B"

        var value = safeBytes.toDouble()
        var unitIndex = -1
        do {
            value /= 1024.0
            unitIndex++
        } while (value >= 1024.0 && unitIndex < units.lastIndex)

        val pattern = if (value >= 100.0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.ROOT, pattern, value, units[unitIndex])
    }

    /**
     * Shows the received byte count, an exact total only when known, and a real measurable
     * percentage when available. Unknown totals are intentionally not estimated or invented.
     */
    fun formatProgress(
        label: String,
        downloadedBytes: Long,
        totalBytes: Long? = null,
        percent: Int? = null,
    ): String {
        val downloaded = downloadedBytes.coerceAtLeast(0L)
        val validTotal = totalBytes?.takeIf { it > 0L && it >= downloaded }
        val sizeText = if (validTotal != null) {
            "${formatBytes(downloaded)} / ${formatBytes(validTotal)}"
        } else {
            formatBytes(downloaded)
        }
        val percentText = percent?.let { " · ${it.coerceIn(0, 100)}%" }.orEmpty()
        return "$label $sizeText$percentText"
    }
}
