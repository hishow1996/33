package io.translatetube.local.tts

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Small, bounded RIFF/WAVE decoder for Android system-TTS output.
 * Kept free of Android APIs so PCM conversion and malformed files can be JVM-tested.
 */
internal object SystemTtsWavDecoder {
    private const val MIN_WAV_BYTES = 44L
    // decode(File) currently keeps both the encoded bytes and decoded FloatArray live during
    // conversion. Bound both halves so one unusually long system-TTS result cannot spike memory.
    private const val MAX_WAV_BYTES = 16L * 1024L * 1024L
    private const val MAX_FRAMES = 4_000_000

    fun decode(file: File, checkActive: () -> Unit = {}): TtsAudio {
        checkActive()
        val size = file.length()
        if (size !in MIN_WAV_BYTES..MAX_WAV_BYTES) {
            throw IllegalStateException("系统 TTS WAV 文件大小异常：$size 字节")
        }
        val bytes = FileInputStream(file).use { readExactBoundedBytes(it, size, checkActive) }
        checkActive()
        return decode(bytes, checkActive)
    }

    /** Read exactly the previously validated size and detect truncation or concurrent growth. */
    internal fun readExactBoundedBytes(
        input: InputStream,
        expectedSize: Long,
        checkActive: () -> Unit = {},
    ): ByteArray {
        checkActive()
        if (expectedSize !in MIN_WAV_BYTES..MAX_WAV_BYTES) {
            throw IllegalStateException("系统 TTS WAV 文件大小异常：$expectedSize 字节")
        }
        val bytes = ByteArray(expectedSize.toInt())
        var offset = 0
        while (offset < bytes.size) {
            if ((offset and 0x3fff) == 0) checkActive()
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IllegalStateException("系统 TTS WAV 文件在读取时被截断")
            if (count == 0) {
                val single = input.read()
                if (single < 0) throw IllegalStateException("系统 TTS WAV 文件在读取时被截断")
                bytes[offset++] = single.toByte()
            } else {
                offset += count
            }
        }
        checkActive()
        if (input.read() >= 0) {
            throw IllegalStateException("系统 TTS WAV 文件在读取时发生变化，已拒绝不完整音频")
        }
        checkActive()
        return bytes
    }

    fun decode(bytes: ByteArray, checkActive: () -> Unit = {}): TtsAudio {
        checkActive()
        if (bytes.size < MIN_WAV_BYTES) {
            throw IllegalStateException("系统 TTS WAV 文件过短")
        }
        if (bytes.size.toLong() > MAX_WAV_BYTES) {
            throw IllegalStateException("系统 TTS WAV 文件超过 16 MB 内存保护上限")
        }
        fun idAt(offset: Int): String = String(bytes, offset, 4, Charsets.US_ASCII)
        if (idAt(0) != "RIFF" || idAt(8) != "WAVE") {
            throw IllegalStateException("系统 TTS 输出不是有效的 RIFF/WAVE 文件")
        }

        var offset = 12
        var formatCode = 0
        var channels = 1
        var sampleRate = 16_000
        var byteRate = 0
        var blockAlign = 0
        var bitsPerSample = 16
        var dataStart = -1
        var dataLen = 0
        var sawFormatChunk = false
        var sawDataChunk = false

        fun u16(p: Int): Int =
            (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)

        fun i32(p: Int): Int =
            (bytes[p].toInt() and 0xFF) or
                ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                ((bytes[p + 3].toInt() and 0xFF) shl 24)

        // Respect the RIFF container boundary instead of trusting trailing bytes past the
        // length declared by the file. Some truncated WAVs still have stale data after that point.
        val riffSize = i32(4)
        if (riffSize < 4) {
            throw IllegalStateException("系统 TTS WAV RIFF 容器长度无效")
        }
        val riffEndLong = 8L + riffSize.toLong()
        if (riffEndLong > bytes.size.toLong() || riffEndLong < 12L) {
            throw IllegalStateException("系统 TTS WAV RIFF 容器数据不完整")
        }
        val riffEnd = riffEndLong.toInt()

        // RIFF chunk order is not fixed; keep scanning after data until fmt is also found.
        var chunkIndex = 0
        while (offset + 8 <= riffEnd) {
            if ((chunkIndex++ and 0x3ff) == 0) checkActive()
            val id = idAt(offset)
            val chunkSize = i32(offset + 4)
            if (chunkSize < 0) {
                throw IllegalStateException("系统 TTS WAV 块长度无效")
            }
            val chunkDataStart = offset + 8
            val available = (riffEnd - chunkDataStart).coerceAtLeast(0)
            if (chunkSize > available) {
                throw IllegalStateException("系统 TTS WAV 数据不完整（$id 块）")
            }
            when (id) {
                "fmt " -> {
                    if (sawFormatChunk) {
                        throw IllegalStateException("系统 TTS WAV 包含重复 fmt 音频格式块")
                    }
                    if (chunkSize < 16) {
                        throw IllegalStateException("系统 TTS WAV fmt 音频格式块过短")
                    }
                    sawFormatChunk = true
                    formatCode = u16(chunkDataStart)
                    channels = u16(chunkDataStart + 2)
                    sampleRate = i32(chunkDataStart + 4)
                    byteRate = i32(chunkDataStart + 8)
                    blockAlign = u16(chunkDataStart + 12)
                    bitsPerSample = u16(chunkDataStart + 14)

                    // Some Android/OEM TTS engines wrap ordinary PCM or IEEE-float audio in
                    // WAVE_FORMAT_EXTENSIBLE (0xFFFE). Resolve only the canonical PCM/float
                    // subformat GUIDs; never reinterpret an unknown codec as raw PCM.
                    if (formatCode == 0xFFFE) {
                        if (chunkSize < 40) {
                            throw IllegalStateException("系统 TTS WAV extensible fmt 块过短")
                        }
                        val extensionSize = u16(chunkDataStart + 16)
                        if (extensionSize < 22 || 18 + extensionSize > chunkSize) {
                            throw IllegalStateException("系统 TTS WAV extensible 格式扩展长度无效")
                        }
                        val validBitsPerSample = u16(chunkDataStart + 18)
                        if (validBitsPerSample != bitsPerSample) {
                            throw IllegalStateException(
                                "系统 TTS WAV extensible 有效位数与容器位宽不一致"
                            )
                        }
                        val guid = chunkDataStart + 24
                        val subFormatCode = i32(guid)
                        val canonicalGuid = u16(guid + 4) == 0 &&
                            u16(guid + 6) == 0x0010 &&
                            (bytes[guid + 8].toInt() and 0xFF) == 0x80 &&
                            (bytes[guid + 9].toInt() and 0xFF) == 0x00 &&
                            (bytes[guid + 10].toInt() and 0xFF) == 0x00 &&
                            (bytes[guid + 11].toInt() and 0xFF) == 0xAA &&
                            (bytes[guid + 12].toInt() and 0xFF) == 0x00 &&
                            (bytes[guid + 13].toInt() and 0xFF) == 0x38 &&
                            (bytes[guid + 14].toInt() and 0xFF) == 0x9B &&
                            (bytes[guid + 15].toInt() and 0xFF) == 0x71
                        if (!canonicalGuid || subFormatCode !in listOf(1, 3)) {
                            throw IllegalStateException(
                                "系统 TTS WAV extensible 子格式不支持：$subFormatCode"
                            )
                        }
                        formatCode = subFormatCode
                    }
                }
                "data" -> {
                    if (sawDataChunk) {
                        throw IllegalStateException("系统 TTS WAV 包含重复 data 音频数据块")
                    }
                    sawDataChunk = true
                    dataStart = chunkDataStart
                    dataLen = chunkSize
                }
            }
            val next = chunkDataStart.toLong() + chunkSize.toLong() + (chunkSize and 1)
            if (next > riffEnd.toLong()) {
                // RIFF odd-sized chunks must have one padding byte within the container.
                throw IllegalStateException("系统 TTS WAV 块缺少填充字节")
            }
            offset = next.toInt()
        }
        if (offset != riffEnd) {
            throw IllegalStateException("系统 TTS WAV 末尾存在不完整的块头")
        }

        if (formatCode == 0 || dataStart < 0 || dataLen <= 0) {
            throw IllegalStateException("系统 TTS WAV 缺少有效 fmt 或 data 音频块")
        }
        if (sampleRate !in 8_000..192_000) {
            throw IllegalStateException("系统 TTS WAV 采样率无效：$sampleRate")
        }
        if (channels !in 1..8) {
            throw IllegalStateException("系统 TTS WAV 声道数不支持：$channels")
        }
        val bytesPerSample = when {
            formatCode == 1 && bitsPerSample in listOf(8, 16, 24, 32) -> bitsPerSample / 8
            formatCode == 3 && bitsPerSample == 32 -> 4
            else -> throw IllegalStateException("系统 TTS WAV 格式不支持：format=$formatCode, bits=$bitsPerSample")
        }
        val frameSize = bytesPerSample * channels
        if (blockAlign != frameSize) {
            throw IllegalStateException("系统 TTS WAV blockAlign 与声道/采样格式不一致")
        }
        val expectedByteRate = sampleRate.toLong() * frameSize.toLong()
        if (byteRate <= 0 || byteRate.toLong() != expectedByteRate) {
            throw IllegalStateException("系统 TTS WAV byteRate 与采样格式不一致")
        }
        if (dataLen % frameSize != 0) {
            throw IllegalStateException("系统 TTS WAV 音频数据未按完整采样帧对齐")
        }
        val frameCount = dataLen / frameSize
        if (frameCount <= 0 || frameCount > MAX_FRAMES) {
            throw IllegalStateException("系统 TTS WAV 音频帧数无效：$frameCount")
        }

        val samples = FloatArray(frameCount)
        var hasAudibleSample = false
        var p = dataStart
        for (frame in 0 until frameCount) {
            if ((frame and 0x1fff) == 0) checkActive()
            var sum = 0f
            for (channel in 0 until channels) {
                val value = when {
                    formatCode == 3 && bitsPerSample == 32 -> Float.fromBits(i32(p))
                    bitsPerSample == 8 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                    bitsPerSample == 16 -> {
                        val raw = u16(p)
                        (if (raw >= 32768) raw - 65536 else raw) / 32768f
                    }
                    bitsPerSample == 24 -> {
                        var raw = (bytes[p].toInt() and 0xFF) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[p + 2].toInt() and 0xFF) shl 16)
                        if ((raw and 0x800000) != 0) raw = raw or -0x1000000
                        raw / 8_388_608f
                    }
                    else -> i32(p) / 2_147_483_648f
                }
                sum += if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
                p += bytesPerSample
            }
            samples[frame] = (sum / channels).coerceIn(-1f, 1f)
            if (kotlin.math.abs(samples[frame]) > 0.00001f) hasAudibleSample = true
        }
        checkActive()
        if (!hasAudibleSample) {
            throw IllegalStateException("系统 TTS WAV 只有静音采样")
        }
        return TtsAudio(samples, sampleRate)
    }
}
