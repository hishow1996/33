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

    /** Approximate output bytes from an audio track's declared bitrate and duration. */
    fun estimateBytesFromBitrateAndDuration(
        bitRateBitsPerSecond: Long?,
        durationUs: Long,
    ): Long? {
        val bitRate = bitRateBitsPerSecond?.takeIf { it > 0L } ?: return null
        if (durationUs <= 0L) return null
        val estimate = bitRate.toDouble() * durationUs.toDouble() / 8_000_000.0
        if (!estimate.isFinite() || estimate < 1.0 || estimate >= Long.MAX_VALUE.toDouble()) return null
        return estimate.toLong().coerceAtLeast(1L)
    }

    /**
     * Projects the final size from bytes produced over the media timeline processed so far.
     * This is only an estimate and should be displayed as such.
     */
    fun estimateBytesFromObservedProgress(
        downloadedBytes: Long,
        elapsedDurationUs: Long,
        totalDurationUs: Long,
    ): Long? {
        if (downloadedBytes <= 0L || elapsedDurationUs <= 0L || totalDurationUs <= 0L) return null
        val estimate = downloadedBytes.toDouble() * totalDurationUs.toDouble() / elapsedDurationUs.toDouble()
        if (!estimate.isFinite() || estimate < 1.0 || estimate >= Long.MAX_VALUE.toDouble()) return null
        return estimate.toLong().coerceAtLeast(downloadedBytes)
    }

    /**
     * Shows the received byte count, an exact total only when known, and a real measurable
     * percentage when available. Estimated totals are explicitly labeled and never presented
     * as exact server-provided lengths.
     */
    fun formatProgress(
        label: String,
        downloadedBytes: Long,
        totalBytes: Long? = null,
        percent: Int? = null,
        totalIsEstimate: Boolean = false,
    ): String {
        val downloaded = downloadedBytes.coerceAtLeast(0L)
        val validTotal = totalBytes?.takeIf { it > 0L && it >= downloaded }
        val sizeText = when {
            validTotal == null -> formatBytes(downloaded)
            totalIsEstimate -> "${formatBytes(downloaded)} / 预计约 ${formatBytes(validTotal)}"
            else -> "${formatBytes(downloaded)} / ${formatBytes(validTotal)}"
        }
        val percentText = percent?.let { " · ${it.coerceIn(0, 100)}%" }.orEmpty()
        return "$label $sizeText$percentText"
    }
}
