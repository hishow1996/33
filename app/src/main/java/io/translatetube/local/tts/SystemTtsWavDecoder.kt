package io.translatetube.local.tts

import java.io.File

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

    fun decode(file: File): TtsAudio {
        val size = file.length()
        if (size !in MIN_WAV_BYTES..MAX_WAV_BYTES) {
            throw IllegalStateException("系统 TTS WAV 文件大小异常：$size 字节")
        }
        return decode(file.readBytes())
    }

    fun decode(bytes: ByteArray): TtsAudio {
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
        var bitsPerSample = 16
        var dataStart = -1
        var dataLen = 0

        fun u16(p: Int): Int =
            (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)

        fun i32(p: Int): Int =
            (bytes[p].toInt() and 0xFF) or
                ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                ((bytes[p + 3].toInt() and 0xFF) shl 24)

        // RIFF chunk order is not fixed; keep scanning after data until fmt is also found.
        while (offset + 8 <= bytes.size) {
            val id = idAt(offset)
            val chunkSize = i32(offset + 4)
            if (chunkSize < 0) {
                throw IllegalStateException("系统 TTS WAV 块长度无效")
            }
            val chunkDataStart = offset + 8
            val available = (bytes.size - chunkDataStart).coerceAtLeast(0)
            if (chunkSize > available) {
                throw IllegalStateException("系统 TTS WAV 数据不完整（$id 块）")
            }
            when (id) {
                "fmt " -> if (chunkSize >= 16) {
                    formatCode = u16(chunkDataStart)
                    channels = u16(chunkDataStart + 2)
                    sampleRate = i32(chunkDataStart + 4)
                    bitsPerSample = u16(chunkDataStart + 14)
                }
                "data" -> {
                    dataStart = chunkDataStart
                    dataLen = chunkSize
                }
            }
            val next = chunkDataStart.toLong() + chunkSize.toLong() + (chunkSize and 1)
            if (next > bytes.size.toLong()) {
                // RIFF odd-sized chunks must have one padding byte.
                throw IllegalStateException("系统 TTS WAV 块缺少填充字节")
            }
            offset = next.toInt()
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
        if (dataLen % frameSize != 0) {
            throw IllegalStateException("系统 TTS WAV 音频数据未按完整采样帧对齐")
        }
        val frameCount = dataLen / frameSize
        if (frameCount <= 0 || frameCount > MAX_FRAMES) {
            throw IllegalStateException("系统 TTS WAV 音频帧数无效：$frameCount")
        }

        val samples = FloatArray(frameCount)
        var p = dataStart
        for (frame in 0 until frameCount) {
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
        }
        if (samples.none { kotlin.math.abs(it) > 0.00001f }) {
            throw IllegalStateException("系统 TTS WAV 只有静音采样")
        }
        return TtsAudio(samples, sampleRate)
    }
}
