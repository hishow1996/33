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
        val audioGroupId: String? = null,
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
        val audioRenditions = mutableListOf<Pair<Map<String, String>, String?>>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    if (attrs["TYPE"].equals("AUDIO", true)) {
                        // URI is optional by HLS spec. Keep declarations without URI so a default
                        // in-band rendition is not accidentally replaced by another group's external
                        // audio URI simply because the latter is the only one with an address.
                        val uri = attrs["URI"]?.takeIf { it.isNotBlank() }
                            ?.let { resolveHttpUrl(manifestUrl, it) }
                        audioRenditions += attrs to uri
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
                        audioGroupId = attrs["AUDIO"]?.takeIf { it.isNotBlank() },
                    )
                    pendingAttributes = null
                }
            }
        }

        // Select a video variant's declared AUDIO group before unrelated audio-only variants.
        // A master may expose a low-bandwidth commentary/audio-only variant alongside a video
        // variant whose preferred English track lives in an external rendition group.
        val groupedVariant = variants
            .filter { !it.audioGroupId.isNullOrBlank() }
            .minWithOrNull(compareBy<MasterVariant> { it.bandwidth }.thenBy { it.url })

        fun renditionOrder(
            renditions: List<Pair<Map<String, String>, String?>>
        ): List<Pair<Map<String, String>, String?>> = renditions.sortedWith(
            compareByDescending<Pair<Map<String, String>, String?>> {
                it.first["DEFAULT"].equals("YES", true)
            }.thenByDescending {
                it.first["AUTOSELECT"].equals("YES", true)
            },
        )

        if (groupedVariant != null) {
            val matchingRenditions = audioRenditions.filter { (attributes, _) ->
                attributes["GROUP-ID"] == groupedVariant.audioGroupId
            }
            val preferred = renditionOrder(matchingRenditions).firstOrNull()
            if (preferred != null) {
                // URI is optional in HLS. A preferred in-band rendition means the variant URL
                // itself carries the audio; do not jump to a standalone commentary variant.
                return preferred.second ?: groupedVariant.url
            }
        }

        // Only use a standalone audio-only variant when no associated external/in-band audio group
        // could be selected from the chosen video variant.
        val audioVariants = variants.filter { it.audioOnly }
        if (audioVariants.isNotEmpty()) {
            return audioVariants.minWithOrNull(compareBy<MasterVariant> { it.bandwidth }.thenBy { it.url })?.url
        }

        // If codec declarations identify an audio-bearing stream, avoid choosing a video-only
        // variant simply because it has a lower BANDWIDTH value.
        val audioBearingVariants = variants.filter { declaresAudioCodec(it.codecs) }
        if (audioBearingVariants.isNotEmpty()) {
            return audioBearingVariants.minWithOrNull(
                compareBy<MasterVariant> { it.bandwidth }.thenBy { it.url }
            )?.url
        }

        // A malformed master containing only audio media declarations has no variant-to-group
        // association. Permit a sole group, but avoid guessing when several unrelated groups exist.
        val declaredGroups = audioRenditions.mapNotNull { it.first["GROUP-ID"] }.distinct()
        if (variants.isEmpty() && declaredGroups.size == 1) {
            val preferred = renditionOrder(audioRenditions).firstOrNull()
            preferred?.second?.let { return it }
        }

        // No codec or group metadata identifies a better source; the lowest declared variant is
        // the last-resort choice and will still undergo real audio-track validation during ASR.
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

    private fun declaresAudioCodec(codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) return false
        return codecs.split(',').map { it.trim().lowercase(Locale.ROOT) }.any { codec ->
            codec.startsWith("mp4a") || codec.startsWith("ac-3") ||
                codec.startsWith("ec-3") || codec.startsWith("opus") ||
                codec.startsWith("vorbis") || codec.startsWith("flac") ||
                codec.startsWith("alac") || codec.startsWith("mp3") ||
                codec.startsWith("dts") || codec.startsWith("dca")
        }
    }

    private fun isClearlyAudioOnly(codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) return false
        val parts = codecs.split(',').map { it.trim().lowercase(Locale.ROOT) }
        val videoPrefixes = listOf(
            "avc", "hev", "hvc", "vp8", "vp08", "vp9", "vp09", "av01",
            "theora", "dvh", "dva", "mp4v", "mp2v",
        )
        val hasVideo = parts.any { codec -> videoPrefixes.any(codec::startsWith) }
        val hasAudio = parts.any { codec ->
            codec.startsWith("mp4a") || codec.startsWith("ac-3") ||
                codec.startsWith("ec-3") || codec.startsWith("opus") ||
                codec.startsWith("vorbis") || codec.startsWith("flac") ||
                codec.startsWith("alac") || codec.startsWith("mp3") ||
                codec.startsWith("dts") || codec.startsWith("dca")
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
