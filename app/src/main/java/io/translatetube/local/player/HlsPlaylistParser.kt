package io.translatetube.local.player

import java.net.URI
import java.util.Locale

/**
 * Conservative HLS parser for on-demand media that is openly accessible to this app.
 * It selects a declared audio rendition when one exists and declines encrypted, byte-range,
 * discontinuous, live/growing, or otherwise ambiguous playlists instead of producing corrupt PCM.
 */
internal object HlsPlaylistParser {
    const val MAX_SEGMENTS = 20_000
    const val DEFAULT_SEGMENT_RANGE_BYTES = 1L * 1024L * 1024L
    const val MAX_DURATION_MS = 12L * 60L * 60L * 1_000L
    const val MAX_MANIFEST_CHARS = 2 * 1024 * 1024

    data class Segment(
        val index: Int,
        val url: String,
        val durationMs: Long,
    )

    data class MediaPlaylist(
        val playlistUrl: String,
        val segments: List<Segment>,
        val initMapUrl: String?,
        val totalDurationMs: Long,
        val isFinal: Boolean,
    )

    data class MasterVariant(
        val url: String,
        val bandwidth: Long,
        val audioOnly: Boolean,
        val codecs: String?,
    )

    /** Returns the most appropriate next playlist from a master playlist, or null for media playlists. */
    fun selectPlaylistUrl(manifestUrl: String, text: String): String? {
        val lines = normalizedLines(text)
        requireHeader(lines)
        if (isMediaPlaylist(lines)) return null
        if (lines.any { it.startsWith("#EXT-X-SESSION-KEY:", true) } ||
            lines.any { line ->
                line.startsWith("#EXT-X-KEY:", true) &&
                    !parseAttributes(line.substringAfter(':'))["METHOD"].equals("NONE", true)
            }
        ) {
            throw UnsupportedOperationException("该 HLS 播放列表使用加密或 DRM 保护，不能提取音轨")
        }

        val variants = mutableListOf<MasterVariant>()
        var pendingAttributes: Map<String, String>? = null
        var audioRenditions = mutableListOf<Pair<Map<String, String>, String>>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    if (attrs["TYPE"].equals("AUDIO", true)) {
                        val uri = attrs["URI"]?.takeIf { it.isNotBlank() }
                            ?.let { resolveHttpUrl(manifestUrl, it) }
                        if (uri != null) audioRenditions += attrs to uri
                    }
                }
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    pendingAttributes = parseAttributes(line.substringAfter(':'))
                }
                pendingAttributes != null && line.isNotBlank() && !line.startsWith("#") -> {
                    val attrs = pendingAttributes!!
                    val uri = resolveHttpUrl(manifestUrl, line)
                    val codecs = attrs["CODECS"]
                    variants += MasterVariant(
                        url = uri,
                        bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                            ?: attrs["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE,
                        audioOnly = isClearlyAudioOnly(codecs),
                        codecs = codecs,
                    )
                    pendingAttributes = null
                }
            }
        }

        val chosenAudio = audioRenditions.sortedWith(
            compareByDescending<Pair<Map<String, String>, String>> {
                it.first["DEFAULT"].equals("YES", true)
            }.thenByDescending {
                it.first["AUTOSELECT"].equals("YES", true)
            }.thenBy { it.first["LANGUAGE"].orEmpty() }
        ).firstOrNull()?.second
        if (chosenAudio != null) return chosenAudio

        val audioVariants = variants.filter { it.audioOnly }
        if (audioVariants.isNotEmpty()) {
            return audioVariants.minWithOrNull(compareBy<MasterVariant> { it.bandwidth }.thenBy { it.url })?.url
        }

        // A master playlist without a declared audio rendition or an explicit audio-only variant
        // cannot be safely treated as audio-only; return the lowest-bandwidth variant and let the
        // caller decide whether muxed VOD segment processing is permitted.
        return variants.minWithOrNull(compareBy<MasterVariant> { it.bandwidth }.thenBy { it.url })?.url
    }

    fun parseMediaPlaylist(playlistUrl: String, text: String): MediaPlaylist {
        require(text.length <= MAX_MANIFEST_CHARS) { "HLS 播放列表超过大小限制" }
        val lines = normalizedLines(text)
        requireHeader(lines)
        if (!isMediaPlaylist(lines)) {
            throw IllegalArgumentException("HLS 主播放列表尚未解析到媒体音轨")
        }
        if (lines.any { line ->
            line.startsWith("#EXT-X-KEY:", true) &&
                !parseAttributes(line.substringAfter(':'))["METHOD"].equals("NONE", true)
        } || lines.any { it.startsWith("#EXT-X-SESSION-KEY:", true) }) {
            throw UnsupportedOperationException("该 HLS 音轨使用加密或 DRM 保护，不能进行音轨提取")
        }
        if (lines.any { it.startsWith("#EXT-X-BYTERANGE:", true) }) {
            throw UnsupportedOperationException("该 HLS 音轨使用字节范围分片，当前版本不支持安全重组")
        }
        if (lines.any { it.startsWith("#EXT-X-DISCONTINUITY", true) }) {
            throw UnsupportedOperationException("该 HLS 音轨包含不连续时间段，当前版本暂不支持自动拼接")
        }

        var initMapUrl: String? = null
        val segments = mutableListOf<Segment>()
        var nextDuration: Long? = null
        var totalDuration = 0L
        var isFinal = lines.any { it.equals("#EXT-X-ENDLIST", true) } ||
            lines.any { it.equals("#EXT-X-PLAYLIST-TYPE:VOD", true) }

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP:", true) -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    if (!attrs["BYTERANGE"].isNullOrBlank()) {
                        throw UnsupportedOperationException("该 HLS 初始化片段使用字节范围，当前版本暂不支持")
                    }
                    val mapPath = attrs["URI"]?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("HLS 初始化片段缺少 URI")
                    val resolvedMapUrl = resolveHttpUrl(playlistUrl, mapPath)
                    if (initMapUrl != null && initMapUrl != resolvedMapUrl) {
                        throw UnsupportedOperationException(
                            "该 HLS 点播列表中途更换了初始化片段，当前版本不会将不兼容的媒体段错误拼接"
                        )
                    }
                    initMapUrl = resolvedMapUrl
                }
                line.startsWith("#EXTINF:", true) -> {
                    val seconds = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                        ?: throw IllegalArgumentException("HLS 音频时长无效")
                    if (!seconds.isFinite() || seconds <= 0.0 || seconds > MAX_DURATION_MS / 1000.0) {
                        throw IllegalArgumentException("HLS 音频分片时长超出支持范围")
                    }
                    nextDuration = (seconds * 1_000.0).toLong().coerceAtLeast(1L)
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val duration = nextDuration
                        ?: throw IllegalArgumentException("HLS 音频分片缺少 EXTINF 时长")
                    if (segments.size >= MAX_SEGMENTS) {
                        throw IllegalArgumentException("HLS 音频分片数量超过限制")
                    }
                    if (totalDuration > MAX_DURATION_MS - duration) {
                        throw IllegalArgumentException("HLS 音频超过 12 小时限制")
                    }
                    segments += Segment(
                        index = segments.size,
                        url = resolveHttpUrl(playlistUrl, line),
                        durationMs = duration,
                    )
                    totalDuration += duration
                    nextDuration = null
                }
            }
        }
        if (segments.isEmpty()) throw IllegalArgumentException("HLS 播放列表没有可用音频分片")
        if (!isFinal) {
            throw UnsupportedOperationException("该 HLS 播放列表可能是直播或仍在增长的流，当前版本只处理已结束的点播列表")
        }
        if (nextDuration != null) throw IllegalArgumentException("HLS 播放列表末尾的音频分片缺少 URI")
        return MediaPlaylist(playlistUrl, segments, initMapUrl, totalDuration, isFinal)
    }

    fun resolveHttpUrl(baseUrl: String, relativeOrAbsolute: String): String {
        val resolved = runCatching { URI(baseUrl).resolve(URI(relativeOrAbsolute)).normalize() }
            .getOrElse { throw IllegalArgumentException("HLS 媒体 URL 无效", it) }
        if ((!resolved.scheme.equals("https", true) && !resolved.scheme.equals("http", true)) ||
            resolved.host.isNullOrBlank() || resolved.userInfo != null
        ) {
            throw IllegalArgumentException("HLS 只允许可访问的 HTTP/HTTPS 媒体地址")
        }
        return resolved.toASCIIString()
    }

    private fun normalizedLines(text: String): List<String> {
        require(text.length <= MAX_MANIFEST_CHARS) { "HLS 播放列表超过大小限制" }
        return text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
    }

    private fun requireHeader(lines: List<String>) {
        require(lines.firstOrNull()?.equals("#EXTM3U", true) == true) {
            "响应不是有效的 HLS 播放列表"
        }
    }

    private fun isMediaPlaylist(lines: List<String>): Boolean =
        lines.any { it.startsWith("#EXTINF:", true) } ||
            lines.any { it.startsWith("#EXT-X-TARGETDURATION:", true) }

    private fun isClearlyAudioOnly(codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) return false
        val parts = codecs.split(',').map { it.trim().lowercase(Locale.ROOT) }
        val videoPrefixes = listOf("avc", "hev", "hvc", "vp9", "vp09", "av01", "theora", "dvh")
        val hasVideo = parts.any { codec -> videoPrefixes.any(codec::startsWith) }
        val hasAudio = parts.any { codec ->
            codec.startsWith("mp4a") || codec.startsWith("ac-3") ||
                codec.startsWith("ec-3") || codec.startsWith("opus") ||
                codec.startsWith("vorbis") || codec.startsWith("flac") ||
                codec.startsWith("alac")
        }
        return hasAudio && !hasVideo
    }

    /** Parses RFC-style attributes while preserving commas inside quoted CODECS/LANGUAGE values. */
    internal fun parseAttributes(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val field = StringBuilder()
        var quoted = false
        val pieces = mutableListOf<String>()
        value.forEach { char ->
            when (char) {
                '"' -> {
                    quoted = !quoted
                    field.append(char)
                }
                ',' -> if (quoted) field.append(char) else {
                    pieces += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
        }
        if (field.isNotEmpty()) pieces += field.toString()
        pieces.forEach { item ->
            val equals = item.indexOf('=')
            if (equals > 0) {
                val key = item.substring(0, equals).trim().uppercase(Locale.ROOT)
                val raw = item.substring(equals + 1).trim()
                val valuePart = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
                    raw.substring(1, raw.length - 1)
                } else raw
                result[key] = valuePart
            }
        }
        return result
    }
}
