package io.translatetube.local.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** 任意音频源 → 16kHz 单声道 PCM；文件形式用于长音频，避免一次性保留多份大数组。 */
object AudioExtract {

    data class Pcm(val samples: FloatArray, val sampleRate: Int = 16000)

    /** 小端 PCM16 暂存文件。只在需要转写的当前语音段上分配 FloatArray。 */
    data class PcmFile(
        val file: File,
        val sampleCount: Long,
        val sampleRate: Int = 16000,
    ) {
        val durationMs: Long
            get() = if (sampleRate > 0) sampleCount * 1000L / sampleRate else 0L

        fun readFloatSamples(startSample: Long, count: Int): FloatArray {
            require(startSample >= 0L && count >= 0 && startSample <= sampleCount) {
                "无效的 PCM 读取范围"
            }
            require(count.toLong() <= sampleCount - startSample) { "PCM 读取范围超出文件长度" }
            require(count.toLong() * 2L <= Int.MAX_VALUE.toLong()) { "单次读取的 PCM 片段过大" }
            if (count == 0) return FloatArray(0)

            val result = FloatArray(count)
            RandomAccessFile(file, "r").use { input ->
                input.seek(startSample * 2L)
                val bytes = ByteArray(minOf(64 * 1024, maxOf(2L, count.toLong() * 2L)).toInt())
                var written = 0
                while (written < count) {
                    val samplesInChunk = minOf(bytes.size / 2, count - written)
                    val byteCount = samplesInChunk * 2
                    input.readFully(bytes, 0, byteCount)
                    var byteIndex = 0
                    repeat(samplesInChunk) {
                        val raw = (bytes[byteIndex].toInt() and 0xff) or
                            ((bytes[byteIndex + 1].toInt() and 0xff) shl 8)
                        result[written++] = raw.toShort() / 32768f
                        byteIndex += 2
                    }
                }
            }
            return result
        }
    }

    /** Compatibility path for short, bounded operations such as speaker diarization. */
    fun decodeTo16kMono(
        ctx: Context,
        src: Uri?,
        file: File?,
        maxDurationMs: Long = Long.MAX_VALUE,
    ): Pcm {
        val pcmFile = decodeTo16kMonoFile(ctx, src, file, maxDurationMs)
        try {
            if (pcmFile.sampleCount > Int.MAX_VALUE.toLong()) {
                throw IllegalStateException("音频过长，无法一次性加载到内存；请使用分段识别")
            }
            return Pcm(pcmFile.readFloatSamples(0L, pcmFile.sampleCount.toInt()), pcmFile.sampleRate)
        } finally {
            pcmFile.file.delete()
        }
    }

    /**
     * Decode one MediaCodec output buffer at a time, downmix/resample immediately, and spool
     * PCM16 to cache. Long ASR jobs then read VAD windows and Whisper segments from this file.
     */
    fun decodeTo16kMonoFile(
        ctx: Context,
        src: Uri?,
        file: File?,
        maxDurationMs: Long = Long.MAX_VALUE,
        checkActive: () -> Unit = {},
    ): PcmFile {
        PcmTempFilePolicy.cleanupStale(ctx.cacheDir)
        val destination = File.createTempFile("translate-pcm-", ".s16le", ctx.cacheDir)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var success = false
        try {
            when {
                file != null -> extractor.setDataSource(file.absolutePath)
                src != null -> extractor.setDataSource(ctx, src, null)
                else -> throw IllegalStateException("无音频源")
            }
            var trackIdx = -1
            var declaredFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i
                    declaredFormat = candidate
                    break
                }
            }
            if (trackIdx < 0) throw IllegalStateException("未找到音频轨")
            val format = declaredFormat ?: throw IllegalStateException("未找到音频轨格式")
            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("无法读取音频编码格式")
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var sourceRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrElse { throw IllegalStateException("无法读取音频采样率", it) }
            var sourceChannels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrElse { throw IllegalStateException("无法读取音频声道数", it) }
            fun validateOutputFormat(rate: Int, channels: Int) {
                if (rate !in 1_000..384_000) {
                    throw IllegalStateException("音频采样率不受支持：${rate} Hz")
                }
                if (channels !in 1..8) {
                    throw IllegalStateException("音频声道数不受支持：${channels}")
                }
            }
            validateOutputFormat(sourceRate, sourceChannels)

            val rawMaxSourceSamples = if (maxDurationMs == Long.MAX_VALUE) {
                Int.MAX_VALUE.toLong()
            } else {
                (sourceRate.toLong() * sourceChannels * maxDurationMs / 1000L)
                    .coerceAtLeast(sourceChannels.toLong())
            }
            val maxSourceSamples = rawMaxSourceSamples - rawMaxSourceSamples % sourceChannels
            var decodedSourceSamples = 0L
            var outputSamples = 0L
            var resampler: Pcm16StreamingResampler? = null
            var resamplerRate = -1
            var resamplerChannels = -1
            var sawInputEos = false
            var sawOutputEos = false
            val info = MediaCodec.BufferInfo()

            BufferedOutputStream(FileOutputStream(destination), 64 * 1024).use { output ->
                while (!sawOutputEos) {
                    checkActive()
                    if (!sawInputEos) {
                        val inBufferIndex = codec.dequeueInputBuffer(10_000)
                        if (inBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inBufferIndex)
                                ?: throw IllegalStateException("音频解码器没有输入缓冲区")
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inBufferIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outBufferIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outBufferIndex)
                            ?: throw IllegalStateException("音频解码器没有输出缓冲区")
                        val endOffset = info.offset.toLong() + info.size.toLong()
                        if (info.offset < 0 || info.size < 0 || info.size % 2 != 0 ||
                            endOffset > outputBuffer.capacity().toLong()
                        ) {
                            codec.releaseOutputBuffer(outBufferIndex, false)
                            throw IllegalStateException("音频解码缓冲区偏移或长度无效")
                        }

                        // MediaCodec may leave stale bytes outside BufferInfo's valid range.
                        val pcmBytes = outputBuffer.duplicate()
                        pcmBytes.clear()
                        pcmBytes.position(info.offset)
                        pcmBytes.limit(endOffset.toInt())
                        val shortBuffer = pcmBytes.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val availableSamples = shortBuffer.remaining()
                        val remainingSamples = (maxSourceSamples - decodedSourceSamples)
                            .coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val rawTake = minOf(availableSamples, remainingSamples)
                        val take = rawTake - rawTake % sourceChannels
                        if (take > 0) {
                            val decodedChunk = ShortArray(take)
                            shortBuffer.get(decodedChunk, 0, take)
                            decodedSourceSamples += take

                            val activeResampler = resampler ?: Pcm16StreamingResampler(sourceRate, 16_000).also {
                                resampler = it
                                resamplerRate = sourceRate
                                resamplerChannels = sourceChannels
                            }
                            if (sourceRate != resamplerRate || sourceChannels != resamplerChannels) {
                                codec.releaseOutputBuffer(outBufferIndex, false)
                                throw IllegalStateException("音频解码格式在处理中途改变，无法安全转换")
                            }
                            // decodedChunk is newly allocated and not reused; avoid a redundant full copy for mono input.
                            val monoChunk = if (sourceChannels == 1) decodedChunk else PcmChannelMixer.downmixChunk(decodedChunk, sourceChannels)
                            val converted = activeResampler.process(monoChunk)
                            writePcm16Le(output, converted)
                            outputSamples += converted.size
                        }
                        val reachedEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                            decodedSourceSamples >= maxSourceSamples
                        codec.releaseOutputBuffer(outBufferIndex, false)
                        if (reachedEnd) sawOutputEos = true
                    } else if (outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // On Android 9 some audio decoders report their actual PCM output here.
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            sourceChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        validateOutputFormat(sourceRate, sourceChannels)
                        if (resampler != null &&
                            (sourceRate != resamplerRate || sourceChannels != resamplerChannels)
                        ) {
                            throw IllegalStateException("音频解码格式在处理中途改变，无法安全转换")
                        }
                    }
                }

                runCatching { codec.stop() }
                runCatching { codec.release() }
                decoder = null

                val activeResampler = resampler
                    ?: throw IllegalStateException("音频轨没有可解码的 PCM 数据")
                val tail = activeResampler.finish()
                writePcm16Le(output, tail)
                outputSamples += tail.size
                output.flush()
            }

            if (outputSamples <= 0L) throw IllegalStateException("音频轨没有可解码的 PCM 数据")
            success = true
            return PcmFile(destination, outputSamples, 16_000)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
            if (!success) destination.delete()
        }
    }

    private fun writePcm16Le(output: BufferedOutputStream, samples: ShortArray) {
        if (samples.isEmpty()) return
        val bytes = ByteArray(samples.size * 2)
        var byteIndex = 0
        samples.forEach { sample ->
            val value = sample.toInt()
            bytes[byteIndex++] = (value and 0xff).toByte()
            bytes[byteIndex++] = ((value shr 8) and 0xff).toByte()
        }
        output.write(bytes)
    }

    /** Legacy whole-array linear resampling, retained for bounded operations and test references. */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate in 1_000..384_000) { "不支持的输入采样率：${fromRate}" }
        require(toRate in 1_000..384_000) { "不支持的目标采样率：${toRate}" }
        if (input.isEmpty() || fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate
        val outputLength = (input.size / ratio).toInt()
        val output = ShortArray(outputLength)
        for (index in 0 until outputLength) {
            val position = index * ratio
            val left = position.toInt()
            val right = (left + 1).coerceAtMost(input.size - 1)
            val fraction = position - left
            output[index] = (input[left] + (input[right] - input[left]) * fraction).toInt().toShort()
        }
        return output
    }
}
