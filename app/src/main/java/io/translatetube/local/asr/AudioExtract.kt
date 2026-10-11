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
            if (trackIdx < 0 || fmt == null) throw IllegalStateException("未找到音频轨")
            ex.selectTrack(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(fmt, null, null, 0)
            codec.start()

            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val collected = ArrayList<ShortArray>()
            val maxSourceSamples = if (maxDurationMs == Long.MAX_VALUE) Int.MAX_VALUE.toLong() else (srcRate.toLong() * srcChannels * maxDurationMs / 1000L).coerceAtLeast(1L)
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
                    val ob = codec.getOutputBuffer(outIdx)!!
                    val available = info.size / 2
                    val remaining = (maxSourceSamples - collectedSamples).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val take = minOf(available, remaining)
                    if (take > 0) {
                        val shorts = ShortArray(take)
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, take)
                        collected += shorts
                        collectedSamples += take
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || collectedSamples >= maxSourceSamples) sawOutputEOS = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 解码器实际输出格式可能与容器声明不同（如 opus 48k）
                    val nf = codec.outputFormat
                    srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                    srcChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT, srcChannels)
                }
            }
            // 音频已解码，尽早释放 MediaCodec，避免后续重采样/分配大数组时占用硬件解码资源。
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            decoder = null

            val total = collected.sumOf { it.size }
            // 立体声两帧合一路，mono 只需一半容量
            val monoLen = if (srcChannels >= 2) total / 2 else total
            var mono = ShortArray(monoLen)
            var w = 0
            for (chunk in collected) {
                if (srcChannels >= 2) {
                    var i = 0
                    while (i + 1 < chunk.size && w < monoLen) {
                        mono[w++] = ((chunk[i].toInt() + chunk[i + 1].toInt()) / 2).toShort()
                        i += 2
                    }
                } else {
                    val n = chunk.size.coerceAtMost(monoLen - w)
                    System.arraycopy(chunk, 0, mono, w, n)
                    w += n
                }
            }
            // 不再保留原始解码块；减少长音频重采样阶段的瞬时内存峰值。
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
        if (fromRate == toRate) return input
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
