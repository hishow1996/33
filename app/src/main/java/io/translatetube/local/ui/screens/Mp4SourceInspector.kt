package io.translatetube.local.ui.screens

/** One answer to a byte-range request; see [WebRangeReader]. */
internal class WebRangeChunk(
    val bytes: ByteArray,
    /** Full resource length when the server revealed it (Content-Range total, or a short complete body). */
    val totalSize: Long?,
    /**
     * False when the server ignored the Range header. A reader must still return the first bytes
     * for `start == 0`, but returns no bytes for a later `start`.
     */
    val rangeHonored: Boolean,
)

/** Reads `length` bytes starting at `start`; may return fewer at end of file. */
internal fun interface WebRangeReader {
    @Throws(java.io.IOException::class)
    fun read(start: Long, length: Int): WebRangeChunk
}

internal sealed interface Mp4InspectResult {
    data class Parsed(val info: WebCandidateMediaInfo) : Mp4InspectResult

    /** The bytes are a different container (WebM, FLV, TS, an HTML error page...). */
    data class NotMp4(val container: String?, val reason: String) : Mp4InspectResult

    /** It looks like MP4 but its track list could not be read (no Range support, no moov, broken). */
    data class Unreadable(val reason: String) : Mp4InspectResult
}

/**
 * Reads the track list of a remote MP4/MOV/M4V without downloading the media data. Only the box
 * headers and the `moov` metadata are fetched (at most [MAX_MOOV_BYTES]), using a handful of range
 * requests, which also works when `moov` sits at the end of a large file.
 */
internal object Mp4SourceInspector {
    const val HEAD_BYTES = 256 * 1024
    const val HOP_BYTES = 64 * 1024
    const val MAX_MOOV_BYTES = 8 * 1024 * 1024

    private const val MAX_READS = 8
    private const val MAX_TOP_LEVEL_BOXES = 256
    private const val DEFAULT_BUDGET_NANOS = 20_000_000_000L
    private val KNOWN_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "styp", "pnot")

    fun inspect(
        reader: WebRangeReader,
        nanoTime: () -> Long = System::nanoTime,
        budgetNanos: Long = DEFAULT_BUDGET_NANOS,
    ): Mp4InspectResult {
        val deadline = nanoTime() + budgetNanos
        val head = reader.read(0L, HEAD_BYTES)
        val headBytes = head.bytes
        if (headBytes.size < 12) {
            return Mp4InspectResult.Unreadable("文件开头不足 12 字节，无法识别格式")
        }
        detectNonMp4Container(headBytes)?.let { container ->
            return Mp4InspectResult.NotMp4(container, "文件内容是 $container，不是 MP4/MOV")
        }
        if (!looksLikeIsoBmff(headBytes)) {
            return Mp4InspectResult.NotMp4(null, "文件开头不是 MP4/MOV 结构")
        }

        var total: Long? = head.totalSize
            ?: if (headBytes.size < HEAD_BYTES) headBytes.size.toLong() else null
        var window = Window(0L, headBytes)
        var reads = 1

        // Walk the top-level boxes (ftyp, mdat, moov, ...) hopping over big ones with range reads.
        var offset = 0L
        var moovOffset = -1L
        var moovSize = 0L
        var boxCount = 0
        while (boxCount++ < MAX_TOP_LEVEL_BOXES) {
            val known = total
            if (known != null && offset + 8 > known) break
            if (!window.covers(offset, 16)) {
                val shortTail = known != null && window.start <= offset &&
                    window.end >= known && window.covers(offset, 8)
                if (!shortTail) {
                    if (reads >= MAX_READS) {
                        return Mp4InspectResult.Unreadable("读取次数超出上限，仍未定位到 moov")
                    }
                    if (nanoTime() > deadline) return Mp4InspectResult.Unreadable("探测超时")
                    val chunk = reader.read(offset, HOP_BYTES)
                    reads++
                    if (!chunk.rangeHonored) return Mp4InspectResult.Unreadable(NO_RANGE_REASON)
                    if (chunk.bytes.size < 8) break
                    chunk.totalSize?.let { total = it }
                    window = Window(offset, chunk.bytes)
                }
            }
            val rel = (offset - window.start).toInt()
            val size32 = window.bytes.u32(rel)
            val type = window.bytes.fourcc(rel + 4)
            var header = 8
            var size = size32
            if (size32 == 1L) {
                if (!window.covers(offset, 16)) break
                header = 16
                size = window.bytes.u64(rel + 8)
            } else if (size32 == 0L) {
                val t = total ?: break
                size = t - offset
            }
            if (size < header) return Mp4InspectResult.Unreadable("MP4 顶层 box 大小无效")
            if (type == "moov") {
                moovOffset = offset
                moovSize = size
                break
            }
            if (size > Long.MAX_VALUE - offset) break
            offset += size
        }
        if (moovOffset < 0L) {
            return Mp4InspectResult.Unreadable("没有找到 moov（文件不完整，或是没有文件头信息的分片流）")
        }

        // Load moov (capped). Reuse the bytes already in hand when they cover it.
        val want = minOf(moovSize, MAX_MOOV_BYTES.toLong()).toInt()
        val moovBytes: ByteArray
        if (window.covers(moovOffset, want)) {
            val rel = (moovOffset - window.start).toInt()
            moovBytes = window.bytes.copyOfRange(rel, rel + want)
        } else {
            if (nanoTime() > deadline) return Mp4InspectResult.Unreadable("探测超时")
            val chunk = reader.read(moovOffset, want)
            if (!chunk.rangeHonored && moovOffset > 0L) {
                return Mp4InspectResult.Unreadable(NO_RANGE_REASON)
            }
            chunk.totalSize?.let { total = it }
            moovBytes = chunk.bytes
        }
        if (moovBytes.size < 16) return Mp4InspectResult.Unreadable("moov 数据不完整")
        val truncated = moovBytes.size.toLong() < moovSize

        val moov = try {
            parseMoov(moovBytes)
        } catch (error: RuntimeException) {
            return Mp4InspectResult.Unreadable("moov 结构异常：${error.javaClass.simpleName}")
        }
        if (moov.tracks.isEmpty()) {
            return Mp4InspectResult.Unreadable(
                if (truncated) "moov 过大（超过 ${MAX_MOOV_BYTES / 1024 / 1024} MB），没读到轨道信息"
                else "moov 中没有任何轨道"
            )
        }
        val brand = headBytes.takeIf { headBytes.fourcc(4) == "ftyp" }?.fourcc(8)
        val container = if (brand == "qt  ") "MOV" else "MP4"
        return Mp4InspectResult.Parsed(buildInfo(container, moov, total, truncated))
    }

    private const val NO_RANGE_REASON = "服务器不支持分段读取（Range），无法定位音轨信息"

    // ---------------------------------------------------------------------------------------------
    // Container sniffing
    // ---------------------------------------------------------------------------------------------

    /** Names a non-MP4 container from its first bytes, or returns null when it is not recognised. */
    internal fun detectNonMp4Container(h: ByteArray): String? {
        fun starts(vararg values: Int): Boolean =
            h.size >= values.size && values.indices.all { (h[it].toInt() and 0xFF) == values[it] }
        return when {
            starts(0x1A, 0x45, 0xDF, 0xA3) -> "WebM/MKV"
            starts(0x46, 0x4C, 0x56) -> "FLV"
            starts(0x4F, 0x67, 0x67, 0x53) -> "Ogg"
            starts(0x49, 0x44, 0x33) -> "MP3"
            starts(0x52, 0x49, 0x46, 0x46) -> "AVI/WAV"
            starts(0x23, 0x45, 0x58, 0x54, 0x4D, 0x33, 0x55) -> "HLS 清单"
            h.size > 188 && starts(0x47) && (h[188].toInt() and 0xFF) == 0x47 -> "MPEG-TS"
            starts(0x3C) || starts(0x7B) || starts(0xEF, 0xBB, 0xBF) -> "网页/文本"
            else -> null
        }
    }

    private fun looksLikeIsoBmff(h: ByteArray): Boolean {
        val size = h.u32(0)
        return (size == 0L || size == 1L || size >= 8L) && h.fourcc(4) in KNOWN_FIRST_BOXES
    }

    // ---------------------------------------------------------------------------------------------
    // moov parsing
    // ---------------------------------------------------------------------------------------------

    private class Window(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
        fun covers(offset: Long, length: Int): Boolean = offset >= start && offset + length <= end
    }

    private enum class TrackKind { VIDEO, AUDIO, OTHER }

    private class TrackAcc {
        var handler: String? = null
        var mdhdTimescale = 0L
        var mdhdDuration = 0L
        var sampleCount = 0L
        var sampleBytes: Long? = null
        var entryType: String? = null
        var entryBody = -1
        var entryEnd = -1
        var codec: String? = null
        var width: Int? = null
        var height: Int? = null
        var channels: Int? = null
        var sampleRate: Int? = null
        var declaredBitrate: Long? = null

        val kind: TrackKind
            get() = when (handler) {
                "vide" -> TrackKind.VIDEO
                "soun" -> TrackKind.AUDIO
                else -> TrackKind.OTHER
            }

        fun durationSeconds(): Double? =
            if (mdhdTimescale > 0L && mdhdDuration > 0L) mdhdDuration.toDouble() / mdhdTimescale else null

        /** Real bitrate from sample sizes when available, otherwise the declared value. */
        fun bitrate(): Pair<Long, WebBitrateBasis>? {
            val seconds = durationSeconds()
            val bytes = sampleBytes
            if (bytes != null && bytes > 0L && seconds != null && seconds > 0.0) {
                val bps = (bytes * 8.0 / seconds).toLong()
                if (bps in 1L..MAX_PLAUSIBLE_BPS) return bps to WebBitrateBasis.MEASURED
            }
            val declared = declaredBitrate
            if (declared != null && declared in 1L..MAX_PLAUSIBLE_BPS) {
                return declared to WebBitrateBasis.DECLARED
            }
            return null
        }
    }

    private class MoovParse(
        val movieTimescale: Long,
        val movieDuration: Long,
        val tracks: List<TrackAcc>,
    )

    private const val MAX_PLAUSIBLE_BPS = 2_000_000_000L

    private fun parseMoov(moov: ByteArray): MoovParse {
        val header = if (moov.u32(0) == 1L) 16 else 8
        var movieTimescale = 0L
        var movieDuration = 0L
        val tracks = mutableListOf<TrackAcc>()
        moov.forEachBox(header, moov.size) { type, bodyStart, bodyEnd ->
            when (type) {
                "mvhd" -> {
                    val version = moov.u8(bodyStart)
                    if (version == 1) {
                        movieTimescale = moov.u32(bodyStart + 20)
                        movieDuration = moov.u64(bodyStart + 24)
                    } else {
                        movieTimescale = moov.u32(bodyStart + 12)
                        movieDuration = moov.u32(bodyStart + 16)
                    }
                }
                "trak" -> tracks += parseTrak(moov, bodyStart, bodyEnd)
                else -> Unit
            }
        }
        return MoovParse(movieTimescale, movieDuration, tracks)
    }

    private fun parseTrak(b: ByteArray, start: Int, end: Int): TrackAcc {
        val track = TrackAcc()
        // A truncated or odd box aborts the walk but keeps whatever was already learned.
        try {
            b.forEachBox(start, end) { type, bodyStart, bodyEnd ->
                if (type == "mdia") {
                    b.forEachBox(bodyStart, bodyEnd) { mdiaType, mdiaStart, mdiaEnd ->
                        when (mdiaType) {
                            "mdhd" -> {
                                if (b.u8(mdiaStart) == 1) {
                                    track.mdhdTimescale = b.u32(mdiaStart + 20)
                                    track.mdhdDuration = b.u64(mdiaStart + 24)
                                } else {
                                    track.mdhdTimescale = b.u32(mdiaStart + 12)
                                    track.mdhdDuration = b.u32(mdiaStart + 16)
                                }
                            }
                            "hdlr" -> {
                                if (mdiaEnd - mdiaStart >= 12) track.handler = b.fourcc(mdiaStart + 8)
                            }
                            "minf" -> parseMinf(b, mdiaStart, mdiaEnd, track)
                            else -> Unit
                        }
                    }
                }
            }
        } catch (error: RuntimeException) {
            // keep partial track data
        }
        try {
            parseSampleEntry(b, track)
        } catch (error: RuntimeException) {
            // keep partial track data
        }
        return track
    }

    private fun parseMinf(b: ByteArray, start: Int, end: Int, track: TrackAcc) {
        b.forEachBox(start, end) { type, bodyStart, bodyEnd ->
            if (type == "stbl") {
                b.forEachBox(bodyStart, bodyEnd) { stblType, stblStart, stblEnd ->
                    when (stblType) {
                        "stsd" -> {
                            // fullbox(4) + entry_count(4), then the first sample entry box.
                            if (stblEnd - stblStart >= 16) {
                                val entrySize = b.u32(stblStart + 8)
                                track.entryType = b.fourcc(stblStart + 12)
                                track.entryBody = stblStart + 16
                                track.entryEnd = minOf(
                                    stblEnd.toLong(),
                                    stblStart + 8L + entrySize,
                                ).toInt()
                            }
                        }
                        "stsz" -> parseStsz(b, stblStart, stblEnd, track)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun parseStsz(b: ByteArray, start: Int, end: Int, track: TrackAcc) {
        if (end - start < 12) return
        val fixedSize = b.u32(start + 4)
        val count = b.u32(start + 8)
        track.sampleCount = count
        if (fixedSize > 0L) {
            if (count <= Long.MAX_VALUE / fixedSize) track.sampleBytes = fixedSize * count
        } else if (start + 12L + count * 4L <= end) {
            var sum = 0L
            var p = start + 12
            repeat(count.toInt()) {
                sum += b.u32(p)
                p += 4
            }
            track.sampleBytes = sum
        }
        // Otherwise the table is cut off by the moov size cap: leave the size unknown.
    }

    private fun parseSampleEntry(b: ByteArray, track: TrackAcc) {
        val type = track.entryType ?: return
        val body = track.entryBody
        val end = track.entryEnd
        if (body < 0 || end - body < 28) return
        when (track.kind) {
            TrackKind.VIDEO -> {
                track.codec = WebMediaFormat.codecName(type)
                track.width = b.u16(body + 24).takeIf { it > 0 }
                track.height = b.u16(body + 26).takeIf { it > 0 }
                if (end - body >= 78) scanEntryChildren(b, body + 78, end, track)
            }
            TrackKind.AUDIO -> {
                track.codec = WebMediaFormat.codecName(type)
                val version = b.u16(body + 8)
                var childStart = body + 28
                if (version == 0 || version == 1) {
                    track.channels = b.u16(body + 16).takeIf { it > 0 }
                    track.sampleRate = (b.u32(body + 24) ushr 16).toInt().takeIf { it > 0 }
                }
                if (version == 1) childStart += 16 else if (version == 2) childStart += 36
                scanEntryChildren(b, childStart, end, track)
            }
            TrackKind.OTHER -> Unit
        }
    }

    private fun scanEntryChildren(b: ByteArray, start: Int, end: Int, track: TrackAcc) {
        b.forEachBox(start, end) { type, bodyStart, bodyEnd ->
            try {
                when (type) {
                    "btrt" -> if (bodyEnd - bodyStart >= 12) {
                        val average = b.u32(bodyStart + 8)
                        if (average > 0L) track.declaredBitrate = average
                    }
                    "esds" -> parseEsds(b, bodyStart, bodyEnd, track)
                    "dOps" -> if (bodyEnd - bodyStart >= 2) {
                        track.channels = b.u8(bodyStart + 1).takeIf { it > 0 }
                    }
                    "dac3" -> if (bodyEnd - bodyStart >= 3) parseDac3(b, bodyStart, track)
                    "dec3" -> if (bodyEnd - bodyStart >= 4) parseDec3(b, bodyStart, track)
                    else -> Unit
                }
            } catch (error: RuntimeException) {
                // a malformed codec box only loses that one detail
            }
        }
    }

    private val AC3_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)
    private val AC3_KBPS = intArrayOf(
        32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640,
    )

    private fun parseDac3(b: ByteArray, start: Int, track: TrackAcc) {
        val value = (b.u8(start) shl 16) or (b.u8(start + 1) shl 8) or b.u8(start + 2)
        val acmod = (value ushr 11) and 0x7
        val lfe = (value ushr 10) and 0x1
        val rateCode = (value ushr 5) and 0x1F
        track.channels = AC3_CHANNELS[acmod] + lfe
        if (rateCode < AC3_KBPS.size && track.declaredBitrate == null) {
            track.declaredBitrate = AC3_KBPS[rateCode] * 1000L
        }
    }

    private fun parseDec3(b: ByteArray, start: Int, track: TrackAcc) {
        val dataRateKbps = (b.u8(start) shl 5) or (b.u8(start + 1) ushr 3)
        val flags = b.u8(start + 3)
        val acmod = (flags ushr 1) and 0x7
        val lfe = flags and 0x1
        track.channels = AC3_CHANNELS[acmod] + lfe
        if (dataRateKbps > 0 && track.declaredBitrate == null) {
            track.declaredBitrate = dataRateKbps * 1000L
        }
    }

    /** Reads the MPEG-4 elementary stream descriptor: declared average bitrate and AAC config. */
    private fun parseEsds(b: ByteArray, bodyStart: Int, bodyEnd: Int, track: TrackAcc) {
        var p = bodyStart + 4 // version + flags
        if (p >= bodyEnd) return
        if (b.u8(p) == 0x03) { // ES_Descriptor
            p = skipDescriptorLength(b, p + 1)
            p += 2 // ES_ID
            val flags = b.u8(p)
            p++
            if (flags and 0x80 != 0) p += 2
            if (flags and 0x40 != 0) p += 1 + b.u8(p)
            if (flags and 0x20 != 0) p += 2
        }
        if (p >= bodyEnd || b.u8(p) != 0x04) return // DecoderConfigDescriptor
        p = skipDescriptorLength(b, p + 1)
        if (p + 13 > bodyEnd) return
        val objectType = b.u8(p)
        val average = b.u32(p + 9)
        if (average > 0L) track.declaredBitrate = average
        if (objectType == 0x6B || objectType == 0x69) track.codec = "MP3"
        p += 13
        if (objectType != 0x40 || p >= bodyEnd || b.u8(p) != 0x05) return
        p = skipDescriptorLength(b, p + 1)
        if (p + 2 > bodyEnd) return
        parseAudioSpecificConfig(b, p, track)
    }

    private fun skipDescriptorLength(b: ByteArray, start: Int): Int {
        var p = start
        repeat(4) {
            val byte = b.u8(p)
            p++
            if (byte and 0x80 == 0) return p
        }
        return p
    }

    private fun parseAudioSpecificConfig(b: ByteArray, start: Int, track: TrackAcc) {
        var bit = 0
        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = b.u8(start + (bit shr 3))
                value = (value shl 1) or ((byte ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return value
        }
        var objectType = bits(5)
        if (objectType == 31) objectType = 32 + bits(6)
        if (bits(4) == 15) bits(24)
        val channelConfig = bits(4)
        track.codec = when (objectType) {
            2 -> "AAC-LC"
            5 -> "HE-AAC"
            29 -> "HE-AACv2"
            1 -> "AAC Main"
            else -> "AAC"
        }
        // With parametric stereo the core is mono but the output is stereo; keep the entry's value.
        if (objectType != 29) {
            val fromConfig = when (channelConfig) {
                in 1..6 -> channelConfig
                7 -> 8
                else -> null
            }
            if (fromConfig != null) track.channels = fromConfig
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Result assembly
    // ---------------------------------------------------------------------------------------------

    private fun buildInfo(
        container: String,
        moov: MoovParse,
        totalSize: Long?,
        truncated: Boolean,
    ): WebCandidateMediaInfo {
        val videoTrack = moov.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
        val audioTracks = moov.tracks.filter { it.kind == TrackKind.AUDIO }
        val audioTrack = audioTracks.firstOrNull()

        val movieSeconds = if (moov.movieTimescale > 0L && moov.movieDuration in 1 until 0xFFFFFFFFL) {
            moov.movieDuration.toDouble() / moov.movieTimescale
        } else {
            moov.tracks.mapNotNull { it.durationSeconds() }.maxOrNull()
        }

        val audioBitrate = audioTrack?.bitrate()
        var videoBitrate = videoTrack?.bitrate()
        if (videoTrack != null && videoBitrate == null &&
            totalSize != null && totalSize > 0L && movieSeconds != null && movieSeconds > 0.0
        ) {
            // Last resort: whole-file average minus the audio we know about. Always labelled "约".
            val overall = (totalSize * 8.0 / movieSeconds).toLong()
            val estimate = overall - (audioBitrate?.first ?: 0L)
            if (estimate in 1L..MAX_PLAUSIBLE_BPS) videoBitrate = estimate to WebBitrateBasis.ESTIMATED
        }

        return WebCandidateMediaInfo(
            container = container,
            durationSeconds = movieSeconds,
            hasVideo = when {
                videoTrack != null -> true
                truncated -> null
                else -> false
            },
            hasAudio = when {
                audioTracks.isNotEmpty() -> true
                truncated -> null // the audio track may sit beyond the part of moov that was read
                else -> false
            },
            audioTrackCount = audioTracks.size,
            videoCodec = videoTrack?.codec,
            videoWidth = videoTrack?.width,
            videoHeight = videoTrack?.height,
            videoBitrateBps = videoBitrate?.first,
            videoBitrateBasis = videoBitrate?.second ?: WebBitrateBasis.DECLARED,
            audioCodec = audioTrack?.codec,
            audioChannels = audioTrack?.channels,
            audioSampleRate = audioTrack?.sampleRate,
            audioBitrateBps = audioBitrate?.first,
            audioBitrateBasis = audioBitrate?.second ?: WebBitrateBasis.DECLARED,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Byte helpers (throw ArrayIndexOutOfBoundsException on truncated input; callers catch it)
    // ---------------------------------------------------------------------------------------------

    private fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
    private fun ByteArray.u16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)
    private fun ByteArray.u32(o: Int): Long =
        (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
    private fun ByteArray.u64(o: Int): Long = (u32(o) shl 32) or u32(o + 4)
    private fun ByteArray.fourcc(o: Int): String =
        String(charArrayOf(u8(o).toChar(), u8(o + 1).toChar(), u8(o + 2).toChar(), u8(o + 3).toChar()))

    /** Calls [action] for each child box in `[start, end)`, clamping a box that overruns `end`. */
    private fun ByteArray.forEachBox(
        start: Int,
        end: Int,
        action: (type: String, bodyStart: Int, bodyEnd: Int) -> Unit,
    ) {
        var pos = start.toLong()
        val limit = end.toLong().coerceAtMost(size.toLong())
        while (pos + 8 <= limit) {
            val p = pos.toInt()
            var boxSize = u32(p)
            val type = fourcc(p + 4)
            var header = 8
            if (boxSize == 1L) {
                if (pos + 16 > limit) return
                boxSize = u64(p + 8)
                header = 16
            } else if (boxSize == 0L) {
                boxSize = limit - pos
            }
            if (boxSize < header) return
            val boxEnd = if (boxSize > limit - pos) limit else pos + boxSize
            action(type, (pos + header).toInt(), boxEnd.toInt())
            pos = boxEnd
        }
    }
}
