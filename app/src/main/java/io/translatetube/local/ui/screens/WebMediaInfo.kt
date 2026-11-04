package io.translatetube.local.ui.screens

import java.util.Locale

/** How a displayed bitrate was obtained; shown as a prefix so a guess is never presented as a fact. */
internal enum class WebBitrateBasis(val prefix: String, val diagnosticName: String) {
    /** Computed from the real sample sizes and duration found in the file header. */
    MEASURED("", "实测（样本大小 / 时长）"),

    /** Declared by the file header (btrt/esds) or by an HLS manifest. */
    DECLARED("声明 ", "文件或清单声明"),

    /** Rough value from total file size and duration; includes audio and container overhead. */
    ESTIMATED("约 ", "按文件总大小和时长估算"),
}

/**
 * What a source check could learn about the tracks of one webpage candidate. Every field is
 * optional: `null` means "not determined", never "absent" (use [hasAudio] == false for that).
 */
internal data class WebCandidateMediaInfo(
    val container: String? = null,
    val durationSeconds: Double? = null,
    val hasVideo: Boolean? = null,
    val hasAudio: Boolean? = null,
    /** Number of audio tracks/renditions found; 0 when unknown. */
    val audioTrackCount: Int = 0,
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoBitrateBps: Long? = null,
    val videoBitrateBasis: WebBitrateBasis = WebBitrateBasis.DECLARED,
    /** HLS masters only: lowest/highest video rendition, set when more than one quality exists. */
    val videoBitrateMinBps: Long? = null,
    val videoBitrateMaxBps: Long? = null,
    val videoHeightMin: Int? = null,
    val videoHeightMax: Int? = null,
    val videoVariantCount: Int = 0,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    val audioBitrateBps: Long? = null,
    val audioBitrateBasis: WebBitrateBasis = WebBitrateBasis.DECLARED,
    val audioLanguage: String? = null,
) {
    /** e.g. "AAC-LC 立体声 128 kbps"; null when nothing about the audio is known. */
    fun audioSummary(): String? {
        val parts = buildList {
            audioCodec?.let { add(it) }
            audioChannels?.let { add(WebMediaFormat.channels(it)) }
            audioBitrateBps?.let { add(audioBitrateBasis.prefix + WebMediaFormat.bitrate(it)) }
            audioLanguage?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return parts.joinToString(" ").ifBlank { null }
    }

    /** e.g. "1080p H.264 2.4 Mbps" or, for an HLS ladder, "360p–1080p H.264 声明 600 kbps–4.2 Mbps". */
    fun videoSummary(): String? {
        val parts = buildList {
            resolutionText()?.let { add(it) }
            videoCodec?.let { add(it) }
            bitrateText()?.let { add(it) }
        }
        return parts.joinToString(" ").ifBlank { null }
    }

    private fun resolutionText(): String? {
        val min = videoHeightMin
        val max = videoHeightMax
        if (min != null && max != null && min != max) return "${min}p–${max}p"
        return WebMediaFormat.resolutionLabel(videoWidth, videoHeight)
    }

    private fun bitrateText(): String? {
        val min = videoBitrateMinBps
        val max = videoBitrateMaxBps
        if (min != null && max != null && min != max) {
            return videoBitrateBasis.prefix + WebMediaFormat.bitrate(min) + "–" + WebMediaFormat.bitrate(max)
        }
        return videoBitrateBps?.let { videoBitrateBasis.prefix + WebMediaFormat.bitrate(it) }
    }

    /** Structured, URL-free values for the in-app diagnostics page. */
    fun diagnostics(): Map<String, String> = linkedMapOf<String, String>().apply {
        put("容器格式", container ?: "未识别")
        put("是否有视频轨", hasVideo?.toString() ?: "未确定")
        put("是否有音轨", hasAudio?.toString() ?: "未确定")
        put("音轨数量", if (audioTrackCount > 0) audioTrackCount.toString() else "未确定")
        put("时长（秒）", durationSeconds?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "未确定")
        put("视频编码", videoCodec ?: "未确定")
        put("视频分辨率", WebMediaFormat.resolutionLabel(videoWidth, videoHeight) ?: "未确定")
        put("视频码率 bps", videoBitrateBps?.toString() ?: "未确定")
        put("视频码率依据", if (videoBitrateBps != null) videoBitrateBasis.diagnosticName else "无")
        if (videoVariantCount > 0) {
            put("视频档位数量", videoVariantCount.toString())
            put("视频最低码率 bps", videoBitrateMinBps?.toString() ?: "未确定")
            put("视频最高码率 bps", videoBitrateMaxBps?.toString() ?: "未确定")
        }
        put("音频编码", audioCodec ?: "未确定")
        put("音频声道数", audioChannels?.toString() ?: "未确定")
        put("音频采样率 Hz", audioSampleRate?.toString() ?: "未确定")
        put("音频码率 bps", audioBitrateBps?.toString() ?: "未确定")
    }
}

/** Display helpers shared by the MP4 inspector and the HLS master summary. */
internal object WebMediaFormat {

    fun bitrate(bitsPerSecond: Long): String = when {
        bitsPerSecond >= 1_000_000L ->
            String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        bitsPerSecond >= 1_000L -> "${(bitsPerSecond + 500L) / 1_000L} kbps"
        else -> "$bitsPerSecond bps"
    }

    fun channels(count: Int): String = when (count) {
        1 -> "单声道"
        2 -> "立体声"
        6 -> "5.1 声道"
        8 -> "7.1 声道"
        else -> "$count 声道"
    }

    /** Landscape video is named by height ("1080p"), portrait by width, like the platforms do. */
    fun resolutionLabel(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return "${if (width >= height) height else width}p"
    }

    /** Short resolution side used for ranking and ranges, with the same landscape/portrait rule. */
    fun resolutionSide(width: Int?, height: Int?): Int? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return if (width >= height) height else width
    }

    /**
     * Maps an ISO-BMFF sample-entry fourcc ("avc1") or an RFC 6381 codec string ("mp4a.40.2")
     * to a human name. Returns null for codecs this app has no friendly name for.
     */
    fun codecName(raw: String): String? {
        val code = raw.trim().lowercase(Locale.ROOT)
        if (code.isEmpty()) return null
        return when (code.substringBefore('.')) {
            "avc1", "avc3" -> "H.264"
            "hvc1", "hev1" -> "H.265"
            "dvh1", "dvhe" -> "Dolby Vision"
            "av01" -> "AV1"
            "vp09", "vp9" -> "VP9"
            "vp08", "vp8" -> "VP8"
            "mp4v" -> "MPEG-4 Visual"
            "mp4a" -> when (code.substringAfter('.', "")) {
                "40.2" -> "AAC-LC"
                "40.5" -> "HE-AAC"
                "40.29" -> "HE-AACv2"
                "40.1" -> "AAC Main"
                "40.34", "6b", "69" -> "MP3"
                else -> "AAC"
            }
            "ac-3", "ac3" -> "AC-3"
            "ec-3", "eac3" -> "E-AC-3"
            "opus" -> "Opus"
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "vorbis" -> "Vorbis"
            "mp3", ".mp3" -> "MP3"
            "sowt", "twos", "lpcm", "ipcm", "in24", "in32", "fl32", "fl64" -> "PCM"
            else -> null
        }
    }

    /** "mm:ss" or "h:mm:ss". */
    fun duration(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
