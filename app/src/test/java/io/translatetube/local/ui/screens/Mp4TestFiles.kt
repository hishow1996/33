package io.translatetube.local.ui.screens

import java.io.ByteArrayOutputStream

/** Builds tiny but structurally real MP4 files for the source-inspector tests. */
internal object Mp4TestFiles {

    private fun u32(value: Long) = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = cat(*payload)
        return cat(u32(8L + body.size), ascii(type), body)
    }

    private fun fullBox(type: String, vararg payload: ByteArray) =
        box(type, byteArrayOf(0, 0, 0, 0), *payload)

    fun ftyp(brand: String = "isom") =
        box("ftyp", ascii(brand), u32(512), ascii("isom"), ascii("mp41"))

    fun mvhd(timescale: Long, duration: Long) =
        fullBox("mvhd", u32(0), u32(0), u32(timescale), u32(duration), ByteArray(80))

    /** AVC video sample entry; children (e.g. btrt) are appended after the fixed fields. */
    fun avc1(width: Int, height: Int, vararg children: ByteArray) = box(
        "avc1",
        ByteArray(6), u16(1), ByteArray(16), u16(width), u16(height),
        u32(0x00480000), u32(0x00480000), u32(0), u16(1), ByteArray(32), u16(0x18), u16(0xFFFF),
        *children,
    )

    /** AAC (mp4a) audio sample entry with an esds box describing AAC-LC and [avgBitrate]. */
    fun mp4a(
        channels: Int,
        sampleRate: Int,
        avgBitrate: Long,
        channelConfig: Int = channels,
        audioObjectType: Int = 2,
    ): ByteArray {
        // AudioSpecificConfig: 5 bits object type, 4 bits frequency index (4 = 44.1 kHz), 4 bits channels.
        val config = (audioObjectType shl 11) or (4 shl 7) or (channelConfig shl 3)
        val specific = cat(byteArrayOf(0x05, 0x02), u16(config))
        val decoderConfig = cat(
            byteArrayOf(0x40, 0x15), byteArrayOf(0, 0, 0), u32(avgBitrate), u32(avgBitrate), specific,
        )
        val decoder = cat(byteArrayOf(0x04, decoderConfig.size.toByte()), decoderConfig)
        val es = cat(byteArrayOf(0, 0, 0), decoder)
        val descriptor = cat(byteArrayOf(0x03, es.size.toByte()), es)
        return box(
            "mp4a",
            ByteArray(6), u16(1), ByteArray(8), u16(channels), u16(16), u16(0), u16(0), u32(sampleRate.toLong() shl 16),
            fullBox("esds", descriptor),
        )
    }

    fun ac3(acmod: Int, lfe: Int, rateCode: Int): ByteArray {
        val value = (acmod shl 11) or (lfe shl 10) or (rateCode shl 5)
        return box(
            "ac-3",
            ByteArray(6), u16(1), ByteArray(8), u16(2), u16(16), u16(0), u16(0), u32(48000L shl 16),
            box("dac3", byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())),
        )
    }

    fun btrt(averageBitrate: Long) = box("btrt", u32(0), u32(averageBitrate), u32(averageBitrate))

    /** A track. Give [sampleSizes] for a real sample table, or leave it empty for none. */
    fun trak(
        handler: String,
        timescale: Long,
        duration: Long,
        entry: ByteArray,
        sampleSizes: List<Int> = emptyList(),
        fixedSampleSize: Int = 0,
        fixedSampleCount: Int = 0,
    ): ByteArray {
        val sizes = if (sampleSizes.isNotEmpty()) {
            fullBox(
                "stsz", u32(0), u32(sampleSizes.size.toLong()),
                cat(*sampleSizes.map { u32(it.toLong()) }.toTypedArray()),
            )
        } else {
            fullBox("stsz", u32(fixedSampleSize.toLong()), u32(fixedSampleCount.toLong()))
        }
        val stbl = box("stbl", fullBox("stsd", u32(1), entry), sizes)
        val mdhd = fullBox("mdhd", u32(0), u32(0), u32(timescale), u32(duration), u16(0x55C4), u16(0))
        val hdlr = fullBox("hdlr", u32(0), ascii(handler), ByteArray(12), byteArrayOf(0))
        return box("trak", fullBox("tkhd", ByteArray(80)), box("mdia", mdhd, hdlr, box("minf", stbl)))
    }

    fun moov(vararg children: ByteArray) = box("moov", *children)

    /** A file whose media data is [mdatBytes] long, with `moov` either first (faststart) or last. */
    fun file(moov: ByteArray, mdatBytes: Int, moovFirst: Boolean, brand: String = "isom"): ByteArray {
        val mdat = box("mdat", ByteArray(mdatBytes))
        return if (moovFirst) cat(ftyp(brand), moov, mdat) else cat(ftyp(brand), mdat, moov)
    }

    /** A 10 s file: 1080p AVC at 800 kbps and 44.1 kHz stereo AAC-LC at ~138 kbps. */
    fun typicalMovie(moovFirst: Boolean, mdatBytes: Int = 200_000): ByteArray = file(
        moov(
            mvhd(1000, 10_000),
            trak("vide", 90_000, 900_000, avc1(1920, 1080), sampleSizes = List(100) { 10_000 }),
            trak("soun", 44_100, 441_000, mp4a(2, 44_100, 128_000), sampleSizes = List(430) { 400 }),
        ),
        mdatBytes,
        moovFirst,
    )
}

/** Serves a byte array with HTTP-Range-like semantics and records what was asked for. */
internal class InMemoryRangeReader(
    private val data: ByteArray,
    private val supportsRange: Boolean = true,
) : WebRangeReader {
    var requests = 0
        private set
    var bytesServed = 0L
        private set

    override fun read(start: Long, length: Int): WebRangeChunk {
        requests++
        if (!supportsRange && start > 0L) return WebRangeChunk(ByteArray(0), null, false)
        if (start >= data.size) return WebRangeChunk(ByteArray(0), data.size.toLong(), supportsRange)
        val end = minOf(data.size.toLong(), start + length).toInt()
        val bytes = data.copyOfRange(start.toInt(), end)
        bytesServed += bytes.size
        return WebRangeChunk(bytes, if (supportsRange) data.size.toLong() else null, supportsRange)
    }
}
