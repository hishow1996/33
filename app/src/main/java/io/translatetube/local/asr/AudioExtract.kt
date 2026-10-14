package io.translatetube.local.asr

import android.content.Context
import android.media.AudioFormat
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

    // Keep compatibility reads bounded on memory-constrained phones.
    private const val MAX_FLOAT_ARRAY_BYTES = 32L * 1024L * 1024L
    // Splitting after downmix guarantees each 16 kHz resampler call stays bounded even for
    // unusual but valid low-rate audio formats accepted by the decoder.
    private const val MAX_RESAMPLER_INPUT_CHUNK_SAMPLES = 64 * 1024

    data class Pcm(val samples: FloatArray, val sampleRate: Int = 16000)

    /** 小端 PCM16 暂存文件。只在需要转写的当前语音段上分配 FloatArray。 */
    data class PcmFile(
        val file: File,
        val sampleCount: Long,
        val sampleRate: Int = 16000,
    ) {
        val durationMs: Long
            get() {
                if (sampleRate <= 0 || sampleCount <= 0L) return 0L
                val rate = sampleRate.toLong()
                val wholeSeconds = sampleCount / rate
                val remainderSamples = sampleCount % rate
                if (wholeSeconds > Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE
                val wholeMs = wholeSeconds * 1_000L
                val remainderMs = remainderSamples * 1_000L / rate
                return if (wholeMs > Long.MAX_VALUE - remainderMs) {
                    Long.MAX_VALUE
                } else {
                    wholeMs + remainderMs
                }
            }

        fun readFloatSamples(
            startSample: Long,
            count: Int,
            checkActive: () -> Unit = {},
        ): FloatArray {
            require(startSample >= 0L && count >= 0 && startSample <= sampleCount) {
                "无效的 PCM 读取范围"
            }
            require(count.toLong() <= sampleCount - startSample) { "PCM 读取范围超出文件长度" }
            require(count.toLong() * 2L <= Int.MAX_VALUE.toLong()) { "单次读取的 PCM 片段过大" }
            require(count.toLong() * 4L <= AudioExtract.MAX_FLOAT_ARRAY_BYTES) {
                "单次 PCM 浮点读取超过 32 MB 安全上限，请缩小分段范围"
            }
            if (count == 0) return FloatArray(0)
            // A cancelled/stale ASR task must stop before reserving a potentially large array.
            checkActive()

            val result = FloatArray(count)
            RandomAccessFile(file, "r").use { input ->
                input.seek(startSample * 2L)
                val bytes = ByteArray(minOf(64 * 1024, maxOf(2L, count.toLong() * 2L)).toInt())
                var written = 0
                while (written < count) {
                    checkActive()
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
        checkActive: () -> Unit = {},
    ): Pcm {
        val pcmFile = decodeTo16kMonoFile(
            ctx, src, file, maxDurationMs, checkActive = checkActive,
        )
        try {
            checkActive()
            if (pcmFile.sampleCount > Int.MAX_VALUE.toLong() ||
                pcmFile.sampleCount * 4L > MAX_FLOAT_ARRAY_BYTES
            ) {
                throw IllegalStateException("音频过长，无法安全地一次性加载到内存；请使用分段识别")
            }
            return Pcm(
                pcmFile.readFloatSamples(
                    0L,
                    pcmFile.sampleCount.toInt(),
                    checkActive = checkActive,
                ),
                pcmFile.sampleRate,
            )
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
            fun validatePcmEncoding(encoding: Int) {
                if (encoding != AudioFormat.ENCODING_PCM_16BIT &&
                    encoding != AudioFormat.ENCODING_PCM_FLOAT
                ) {
                    throw IllegalStateException("音频解码器返回了不支持的 PCM 编码：${encoding}")
                }
            }
            validateOutputFormat(sourceRate, sourceChannels)
            var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            validatePcmEncoding(pcmEncoding)

            // Recompute this bound if Android 9 reports the decoder's actual PCM format later.
            var maxSourceSamples = PcmDecodeLimitPolicy.maxSourceSamples(
                sourceRate,
                sourceChannels,
                maxDurationMs,
            )
            var decodedSourceSamples = 0L
            var outputSamples = 0L
            var outputBytes = 0L
            var resampler: Pcm16StreamingResampler? = null
            var resamplerRate = -1
            var resamplerChannels = -1
            var resamplerEncoding = -1
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
                        val bytesPerSample = when (pcmEncoding) {
                            AudioFormat.ENCODING_PCM_16BIT -> 2
                            AudioFormat.ENCODING_PCM_FLOAT -> 4
                            else -> throw IllegalStateException("音频解码器返回了不支持的 PCM 编码：${pcmEncoding}")
                        }
                        if (!PcmFrameLayout.isValidBuffer(
                                offsetBytes = info.offset,
                                sizeBytes = info.size,
                                bytesPerSample = bytesPerSample,
                                channels = sourceChannels,
                            ) || endOffset > outputBuffer.capacity().toLong()
                        ) {
                            codec.releaseOutputBuffer(outBufferIndex, false)
                            throw IllegalStateException("音频解码缓冲区未按 PCM 声道帧对齐，偏移或长度无效")
                        }

                        // MediaCodec may leave stale bytes outside BufferInfo's valid range.
                        val pcmBytes = outputBuffer.duplicate()
                        pcmBytes.clear()
                        pcmBytes.position(info.offset)
                        pcmBytes.limit(endOffset.toInt())
                        val sampleBytes = pcmBytes.slice().order(ByteOrder.LITTLE_ENDIAN)
                        val availableSamples = info.size / bytesPerSample
                        val remainingSamples = (maxSourceSamples - decodedSourceSamples)
                            .coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val rawTake = minOf(availableSamples, remainingSamples)
                        val take = rawTake - rawTake % sourceChannels
                        if (take > 0) {
                            val decodedChunk = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> ShortArray(take).also { samples ->
                                    sampleBytes.asShortBuffer().get(samples, 0, take)
                                }
                                AudioFormat.ENCODING_PCM_FLOAT -> ShortArray(take).also { samples ->
                                    val floats = sampleBytes.asFloatBuffer()
                                    repeat(take) { index ->
                                        if ((index and 0x1fff) == 0) checkActive()
                                        samples[index] = Pcm16SampleConverter.floatToPcm16(floats.get())
                                    }
                                }
                                else -> throw IllegalStateException("音频解码器返回了不支持的 PCM 编码：${pcmEncoding}")
                            }
                            decodedSourceSamples += take

                            val activeResampler = resampler ?: Pcm16StreamingResampler(sourceRate, 16_000).also {
                                resampler = it
                                resamplerRate = sourceRate
                                resamplerChannels = sourceChannels
                                resamplerEncoding = pcmEncoding
                            }
                            if (sourceRate != resamplerRate || sourceChannels != resamplerChannels ||
                                pcmEncoding != resamplerEncoding
                            ) {
                                codec.releaseOutputBuffer(outBufferIndex, false)
                                throw IllegalStateException("音频解码格式在处理中途改变，无法安全转换")
                            }
                            // decodedChunk is newly allocated and not reused; avoid a redundant full copy for mono input.
                            val monoChunk = if (sourceChannels == 1) decodedChunk else PcmChannelMixer.downmixChunk(decodedChunk, sourceChannels, checkActive)
                            // MediaCodec output size is provider-dependent. Feed the resampler bounded
                            // slices so a large valid codec buffer cannot trip its per-call safety cap.
                            var monoOffset = 0
                            while (monoOffset < monoChunk.size) {
                                checkActive()
                                val chunkCount = minOf(
                                    MAX_RESAMPLER_INPUT_CHUNK_SAMPLES,
                                    monoChunk.size - monoOffset,
                                )
                                val resampleInput = if (monoOffset == 0 && chunkCount == monoChunk.size) {
                                    monoChunk
                                } else {
                                    monoChunk.copyOfRange(monoOffset, monoOffset + chunkCount)
                                }
                                val converted = activeResampler.process(resampleInput, checkActive)
                                if (!PcmTempFilePolicy.canAppendSamples(outputBytes, converted.size)) {
                                    throw IllegalStateException(
                                        "解码后的临时音频超过 512 MB 安全上限；请使用较短的视频后再试。"
                                    )
                                }
                                writePcm16Le(output, converted, checkActive)
                                outputBytes += converted.size.toLong() * 2L
                                outputSamples += converted.size
                                monoOffset += chunkCount
                            }
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
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        validateOutputFormat(sourceRate, sourceChannels)
                        validatePcmEncoding(pcmEncoding)
                        if (resampler != null &&
                            (sourceRate != resamplerRate || sourceChannels != resamplerChannels ||
                                pcmEncoding != resamplerEncoding)
                        ) {
                            throw IllegalStateException("音频解码格式在处理中途改变，无法安全转换")
                        }
                        if (resampler == null && decodedSourceSamples == 0L) {
                            maxSourceSamples = PcmDecodeLimitPolicy.maxSourceSamples(
                                sourceRate,
                                sourceChannels,
                                maxDurationMs,
                            )
                        }
                    }
                }

                runCatching { codec.stop() }
                runCatching { codec.release() }
                decoder = null

                val activeResampler = resampler
                    ?: throw IllegalStateException("音频轨没有可解码的 PCM 数据")
                val tail = activeResampler.finish(checkActive)
                if (!PcmTempFilePolicy.canAppendSamples(outputBytes, tail.size)) {
                    throw IllegalStateException(
                        "解码后的临时音频超过 512 MB 安全上限；请使用较短的视频后再试。"
                    )
                }
                writePcm16Le(output, tail, checkActive)
                outputBytes += tail.size.toLong() * 2L
                outputSamples += tail.size
                output.flush()
            }

            if (outputSamples <= 0L) throw IllegalStateException("音频轨没有可解码的 PCM 数据")
            success = true
            return PcmFile(destination, outputSamples, 16_000)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            if (!success) runCatching { destination.delete() }
        }
    }

    private fun writePcm16Le(
        output: BufferedOutputStream,
        samples: ShortArray,
        checkActive: () -> Unit = {},
    ) {
        if (samples.isEmpty()) return
        checkActive()
        // Convert and write small chunks instead of duplicating an entire codec buffer.
        // This bounds temporary memory on older phones and keeps cancellation responsive.
        val bytes = ByteArray(16 * 1024)
        val samplesPerChunk = bytes.size / 2
        var sampleOffset = 0
        while (sampleOffset < samples.size) {
            checkActive()
            val count = minOf(samplesPerChunk, samples.size - sampleOffset)
            var byteIndex = 0
            for (index in sampleOffset until sampleOffset + count) {
                val value = samples[index].toInt()
                bytes[byteIndex++] = (value and 0xff).toByte()
                bytes[byteIndex++] = ((value shr 8) and 0xff).toByte()
            }
            output.write(bytes, 0, byteIndex)
            sampleOffset += count
        }
        checkActive()
    }

    /** Legacy whole-array linear resampling, retained for bounded operations and test references. */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate in 1_000..384_000) { "不支持的输入采样率：${fromRate}" }
        require(toRate in 1_000..384_000) { "不支持的目标采样率：${toRate}" }
        if (input.isEmpty() || fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate
        val outputLengthDouble = input.size.toDouble() / ratio
        val maxOutputSamples = MAX_FLOAT_ARRAY_BYTES / 2L
        if (!outputLengthDouble.isFinite() || outputLengthDouble > maxOutputSamples.toDouble()) {
            throw IllegalStateException("重采样后音频过长，无法安全地一次性分配内存；请使用流式重采样")
        }
        val outputLength = outputLengthDouble.toInt()
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
