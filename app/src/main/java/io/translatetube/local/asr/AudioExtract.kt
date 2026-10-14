package io.translatetube.local.asr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.content.Context
import java.io.File
import java.nio.ByteOrder

/** 任意音频源（本地文件 uri / 网络已下载文件）→ 16kHz 单声道 PCM FloatArray */
object AudioExtract {

    data class Pcm(val samples: FloatArray, val sampleRate: Int = 16000)

    fun decodeTo16kMono(ctx: Context, src: Uri?, file: File?, maxDurationMs: Long = Long.MAX_VALUE): Pcm {
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            when {
                file != null -> ex.setDataSource(file.absolutePath)
                src != null -> ex.setDataSource(ctx, src, null)
                else -> throw IllegalStateException("无音频源")
            }
            var trackIdx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; fmt = f; break
                }
            }
            if (trackIdx < 0) throw IllegalStateException("未找到音频轨")
            val format = fmt ?: throw IllegalStateException("未找到音频轨格式")
            ex.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var srcRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrElse { throw IllegalStateException("无法读取音频采样率", it) }
            var srcChannels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrElse { throw IllegalStateException("无法读取音频声道数", it) }
            fun validateOutputFormat(rate: Int, channels: Int) {
                if (rate !in 1_000..384_000) {
                    throw IllegalStateException("音频采样率不受支持：${rate} Hz")
                }
                if (channels !in 1..8) {
                    throw IllegalStateException("音频声道数不受支持：$channels")
                }
            }
            validateOutputFormat(srcRate, srcChannels)
            val collected = ArrayList<ShortArray>()
            val rawMaxSourceSamples = if (maxDurationMs == Long.MAX_VALUE) {
                Int.MAX_VALUE.toLong()
            } else {
                (srcRate.toLong() * srcChannels * maxDurationMs / 1000L).coerceAtLeast(srcChannels.toLong())
            }
            val maxSourceSamples = rawMaxSourceSamples - rawMaxSourceSamples % srcChannels
            var collectedSamples = 0L
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inBufIdx = codec.dequeueInputBuffer(10_000)
                    if (inBufIdx >= 0) {
                        val ib = codec.getInputBuffer(inBufIdx)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inBufIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                        ?: throw IllegalStateException("音频解码器没有输出缓冲区")
                    val endOffset = info.offset.toLong() + info.size.toLong()
                    if (info.offset < 0 || info.size < 0 || info.size % 2 != 0 ||
                        endOffset > ob.capacity().toLong()
                    ) {
                        codec.releaseOutputBuffer(outIdx, false)
                        throw IllegalStateException("音频解码缓冲区偏移或长度无效")
                    }
                    // MediaCodec output buffers may contain data outside BufferInfo's valid window.
                    // Read only [offset, offset + size), never stale bytes from the buffer capacity.
                    val pcmBytes = ob.duplicate()
                    pcmBytes.clear()
                    pcmBytes.position(info.offset)
                    pcmBytes.limit(endOffset.toInt())
                    val shortBuffer = pcmBytes.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val available = shortBuffer.remaining()
                    val remaining = (maxSourceSamples - collectedSamples)
                        .coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val rawTake = minOf(available, remaining)
                    // Preserve complete PCM frames when trimming a duration-limited decode.
                    val take = rawTake - rawTake % srcChannels
                    if (take > 0) {
                        val shorts = ShortArray(take)
                        shortBuffer.get(shorts, 0, take)
                        collected += shorts
                        collectedSamples += take
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || collectedSamples >= maxSourceSamples) sawOutputEOS = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 解码器实际输出格式可能与容器声明不同（如 opus 48k）
                    val nf = codec.outputFormat
                    // Use API-16-compatible key checks rather than newer getInteger(key, default)
                    // overloads, because the target devices include Android 9.
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        srcChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    validateOutputFormat(srcRate, srcChannels)
                }
            }
            // 音频已解码，尽早释放 MediaCodec，避免后续重采样/分配大数组时占用硬件解码资源。
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            decoder = null

            if (collected.isEmpty()) throw IllegalStateException("音频轨没有可解码的 PCM 数据")
            var mono = PcmChannelMixer.downmixToMono(collected, srcChannels)
            // Release decoded multichannel blocks before resampling/float conversion.
            collected.clear()
            var resampled = resample(mono, srcRate, 16000)
            mono = ShortArray(0)
            val floats = FloatArray(resampled.size) { resampled[it] / 32768f }
            resampled = ShortArray(0)
            return Pcm(floats, 16000)
        } finally {
            // MediaCodec 也必须在解码异常/取消路径释放，避免连续播放时耗尽硬件解码器资源。
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            ex.release()
        }
    }

    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate in 1_000..384_000) { "不支持的输入采样率：$fromRate" }
        require(toRate in 1_000..384_000) { "不支持的目标采样率：$toRate" }
        if (input.isEmpty() || fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = pos - i0
            out[i] = (input[i0] + (input[i1] - input[i0]) * frac).toInt().toShort()
        }
        return out
    }
}
