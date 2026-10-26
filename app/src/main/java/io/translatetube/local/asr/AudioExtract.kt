package io.translatetube.local.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 任意音频源 → 16kHz 单声道 PCM；文件形式用于长音频，避免一次性保留多份大数组。 */
object AudioExtract {

    // Keep compatibility reads bounded on memory-constrained phones.
    private const val MAX_FLOAT_ARRAY_BYTES = 32L * 1024L * 1024L
    // Splitting after downmix guarantees each 16 kHz resampler call stays bounded even for
    // unusual but valid low-rate audio formats accepted by the decoder.
    private const val MAX_DECODED_SOURCE_CHUNK_SAMPLES = 64 * 1024
    private const val MAX_RESAMPLER_INPUT_CHUNK_SAMPLES = 64 * 1024
    // Some Android 9 codecs can remain in TRY_AGAIN_LATER forever on malformed sources.
    private const val MAX_CODEC_STALL_MS = 60_000L
    private const val CODEC_BLOCKING_WAIT_US = 10_000L
    private const val MAX_INPUT_BUFFERS_PER_PASS = 16

    data class Pcm(val samples: FloatArray, val sampleRate: Int = 16000)

    /** Container metadata read without decoding samples. */
    data class MediaProbe(
        val audioTrackCount: Int,
        val videoTrackCount: Int,
        val durationMs: Long,
        /** Audio MIME, language, sample rate, channel count and bitrate per detected track. */
        val audioTrackDetails: List<String> = emptyList(),
        val selectedAudioMimeType: String? = null,
        val selectedAudioLanguage: String? = null,
        val selectedAudioSampleRateHz: Int? = null,
        val selectedAudioChannelCount: Int? = null,
        val selectedAudioBitrate: Int? = null,
        /** null means Android could not conclusively answer the decoder lookup. */
        val selectedAudioDecoderAvailable: Boolean? = null,
    )

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
            // A cancelled/stale ASR task must stop before file inspection or array allocation.
            checkActive()

            // Validate offset arithmetic and the on-disk range before reserving memory. A stale or
            // truncated PCM file must fail cheaply instead of allocating a buffer and failing later.
            require(sampleCount <= Long.MAX_VALUE / 2L) { "PCM 文件采样范围超出可寻址长度" }
            val endSample = startSample + count.toLong()
            val endByte = endSample * 2L
            val expectedFileBytes = sampleCount * 2L
            require(file.isFile && file.length() == expectedFileBytes && endByte <= expectedFileBytes) {
                "PCM 临时文件长度与声明的采样数不一致"
            }

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
     * Read audio/video track metadata without running a decoder. Direct cloud uploads use this
     * to verify that a cached ".bin" is really one supported audio track and to normalize provider
     * timestamps against the duration of the same track.
     */
    fun probeMediaFile(file: File): MediaProbe {
        if (!file.isFile || file.length() <= 0L) {
            throw IllegalStateException("音频文件不存在或为空")
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioCandidates = ArrayList<AudioTrackSelectionPolicy.Candidate>()
            val audioTrackDetails = ArrayList<String>()
            var audioTrackCount = 0
            var videoTrackCount = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().ifBlank { "unknown" }
                val language = runCatching { format.getString("language").orEmpty() }.getOrDefault("")
                val channelCount = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
                val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
                val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)
                val isDefault = runCatching {
                    format.containsKey("is-default") && format.getInteger("is-default") != 0
                }.getOrDefault(false)
                when {
                    mime.startsWith("audio/") -> {
                        audioTrackCount++
                        audioCandidates += AudioTrackSelectionPolicy.Candidate(
                            index = index, isDefault = isDefault, channelCount = channelCount.coerceAtLeast(1),
                        )
                        audioTrackDetails += buildList {
                            add("轨道=$index")
                            add("MIME=$mime")
                            if (language.isNotBlank()) add("语言=$language")
                            if (sampleRate > 0) add("采样率=${sampleRate}Hz")
                            if (channelCount > 0) add("声道=$channelCount")
                            if (bitrate > 0) add("码率=${bitrate}bps")
                            if (isDefault) add("默认音轨=true")
                        }.joinToString(", ")
                    }
                    mime.startsWith("video/") -> videoTrackCount++
                }
            }
            val selectedTrack = AudioTrackSelectionPolicy.choose(audioCandidates)
            val selectedFormat = selectedTrack?.let(extractor::getTrackFormat)
            val durationUs = selectedFormat?.let { format ->
                runCatching {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                }.getOrDefault(0L).coerceAtLeast(0L)
            } ?: 0L
            val decoderAvailable = selectedFormat?.let { format ->
                runCatching {
                    !MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format).isNullOrBlank()
                }.getOrNull()
            }
            return MediaProbe(
                audioTrackCount = audioTrackCount,
                videoTrackCount = videoTrackCount,
                durationMs = durationUs / 1_000L,
                audioTrackDetails = audioTrackDetails.take(12) +
                    (if (audioTrackDetails.size > 12) listOf("其余 ${audioTrackDetails.size - 12} 条音轨已省略") else emptyList()),
                selectedAudioMimeType = selectedFormat?.getString(MediaFormat.KEY_MIME),
                selectedAudioLanguage = selectedFormat?.let { runCatching { it.getString("language") }.getOrNull() },
                selectedAudioSampleRateHz = selectedFormat?.let {
                    runCatching { it.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
                },
                selectedAudioChannelCount = selectedFormat?.let {
                    runCatching { it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull()
                },
                selectedAudioBitrate = selectedFormat?.let {
                    runCatching { it.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
                },
                selectedAudioDecoderAvailable = decoderAvailable,
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Remux a compressed AAC track into an audio-only M4A container. This copies compressed
     * packets and timestamps; it does not decode/resample them. Unsupported codecs/containers
     * return null so the caller can safely fall back to the existing PCM decoder.
     */
    fun remuxAacAudioTrackToM4a(
        ctx: Context,
        src: Uri? = null,
        file: File? = null,
        checkActive: () -> Unit = {},
        onProgressPercent: (Int) -> Unit = {},
    ): File? {
        if (src == null && file == null) return null
        val destination = try {
            File.createTempFile("asr-cloud-audio-", ".m4a", ctx.cacheDir)
        } catch (_: Exception) {
            return null
        }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputComplete = false
        try {
            when {
                file != null -> extractor.setDataSource(file.absolutePath)
                src != null -> extractor.setDataSource(ctx, src, null)
            }

            val audioCandidates = ArrayList<AudioTrackSelectionPolicy.Candidate>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                val channelCount = runCatching {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(1)
                val isDefault = runCatching {
                    format.containsKey("is-default") && format.getInteger("is-default") != 0
                }.getOrDefault(false)
                audioCandidates += AudioTrackSelectionPolicy.Candidate(
                    index = index,
                    isDefault = isDefault,
                    channelCount = channelCount,
                )
            }
            val trackIndex = AudioTrackSelectionPolicy.choose(audioCandidates) ?: return null
            val inputFormat = extractor.getTrackFormat(trackIndex)
            // MP4/M4A audio remuxing is broadly available on Android 9 for AAC-LC / AAC tracks.
            // Never relabel Opus, AC3 or another compressed stream as M4A.
            if (inputFormat.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm") return null

            val durationUs = runCatching {
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputFormat.getLong(MediaFormat.KEY_DURATION)
                } else 0L
            }.getOrDefault(0L).coerceAtLeast(0L)
            extractor.selectTrack(trackIndex)
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(inputFormat)
            muxer.start()
            muxerStarted = true

            val configuredSize = runCatching {
                inputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(256 * 1024)
            var buffer = ByteBuffer.allocateDirect(configuredSize.coerceIn(64 * 1024, 16 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var lastProgress = -1
            var wroteSample = false

            while (true) {
                checkActive()
                val sampleSize = extractor.sampleSize
                if (sampleSize < 0L) break
                if (sampleSize > 16L * 1024L * 1024L) {
                    throw IllegalStateException("AAC 音频帧超过安全上限")
                }
                if (sampleSize > buffer.capacity().toLong()) {
                    buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.set(0, size, presentationTimeUs, extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(outputTrack, buffer, info)
                wroteSample = true

                if (durationUs > 0L) {
                    val percent = ((presentationTimeUs.toDouble() / durationUs.toDouble()) * 100.0)
                        .toInt().coerceIn(0, 99)
                    if (percent > lastProgress) {
                        lastProgress = percent
                        onProgressPercent(percent)
                    }
                }
                if (!extractor.advance()) break
            }

            checkActive()
            if (!wroteSample) return null
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null
            if (!destination.isFile || destination.length() <= 44L) return null
            outputComplete = true
            onProgressPercent(100)
            return destination
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unsupported container details and muxer/device quirks are handled by the caller's
            // existing decode-to-PCM route, not surfaced as a cloud transcription failure.
            return null
        } finally {
            runCatching { extractor.release() }
            muxer?.let { activeMuxer ->
                if (muxerStarted) runCatching { activeMuxer.stop() }
                runCatching { activeMuxer.release() }
            }
            if (!outputComplete) runCatching { destination.delete() }
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
        onProgressPercent: (Int) -> Unit = {},
    ): PcmFile {
        PcmTempFilePolicy.cleanupStale(ctx.cacheDir)
        // 不限时长的整段解码：长的本地 AAC 音轨先尝试分段并行解码（有校验，不通过会返回 null，
        // 下面照旧顺序解码）。限定时长的短解码（截取片段）不走并行。
        if (maxDurationMs == Long.MAX_VALUE) {
            ParallelPcmDecoder.tryDecode(ctx, src, file, checkActive, onProgressPercent)?.let { return it }
        }
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
            val audioCandidates = ArrayList<AudioTrackSelectionPolicy.Candidate>()
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val channelCount = runCatching {
                        candidate.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }.getOrDefault(1)
                    val isDefault = runCatching {
                        candidate.containsKey("is-default") && candidate.getInteger("is-default") != 0
                    }.getOrDefault(false)
                    audioCandidates += AudioTrackSelectionPolicy.Candidate(
                        index = i,
                        isDefault = isDefault,
                        channelCount = channelCount,
                    )
                }
            }
            val trackIdx = AudioTrackSelectionPolicy.choose(audioCandidates)
                ?: throw IllegalStateException("未找到音频轨")
            val format = extractor.getTrackFormat(trackIdx)
            val mediaDurationUs = runCatching {
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else 0L
            }.getOrDefault(0L).coerceAtLeast(0L)
            var lastProgressPercent = -1
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
            // Reuse one conversion buffer across codec output chunks to avoid steady 16 KiB
            // allocations (and GC churn) for every chunk on memory-constrained devices.
            val pcmWriteBuffer = ByteArray(16 * 1024)

            BufferedOutputStream(FileOutputStream(destination), 64 * 1024).use { output ->
                var lastCodecProgressAtMs = android.os.SystemClock.elapsedRealtime()
                var outputWaitUs = CODEC_BLOCKING_WAIT_US
                while (!sawOutputEos) {
                    checkActive()
                    var loopMadeProgress = false
                    // Keep the codec's input queue full: hand over every free input buffer right
                    // now (non-blocking) instead of one frame per loop. A one-in/one-out ping-pong
                    // makes the app thread and the codec thread wait for each other on every frame.
                    var queuedThisPass = 0
                    while (!sawInputEos && queuedThisPass < MAX_INPUT_BUFFERS_PER_PASS) {
                        val inBufferIndex = codec.dequeueInputBuffer(0)
                        if (inBufferIndex < 0) break
                        val inputBuffer = codec.getInputBuffer(inBufferIndex)
                            ?: throw IllegalStateException("音频解码器没有输入缓冲区")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                            loopMadeProgress = true
                        } else {
                            codec.queueInputBuffer(inBufferIndex, 0, size, extractor.sampleTime, 0)
                            val hasNextSample = extractor.advance()
                            loopMadeProgress = loopMadeProgress ||
                                CodecProgressPolicy.inputMakesProgress(size, hasNextSample)
                        }
                        queuedThisPass++
                    }

                    // Output already waiting is taken without blocking; only wait (up to 10 ms,
                    // woken as soon as a buffer is ready) once the codec has nothing more to give.
                    val outBufferIndex = codec.dequeueOutputBuffer(info, outputWaitUs)
                    outputWaitUs = if (outBufferIndex >= 0 ||
                        outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    ) 0L else CODEC_BLOCKING_WAIT_US
                    if (outBufferIndex >= 0) {
                        loopMadeProgress = loopMadeProgress || CodecProgressPolicy.outputMakesProgress(
                            outputBytes = info.size,
                            endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0,
                        )
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

                            // Bound the first allocation too: decoder providers control BufferInfo.size,
                            // so do not materialize one ShortArray for the entire output buffer.
                            var sourceOffset = 0
                            while (sourceOffset < take) {
                                checkActive()
                                val sourceChunkCount = PcmFrameLayout.boundedChunkSampleCount(
                                    remainingSamples = take - sourceOffset,
                                    maxChunkSamples = MAX_DECODED_SOURCE_CHUNK_SAMPLES,
                                    channels = sourceChannels,
                                )
                                val decodedChunk = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> ShortArray(sourceChunkCount).also { samples ->
                                        sampleBytes.asShortBuffer().apply { position(sourceOffset) }
                                            .get(samples, 0, sourceChunkCount)
                                    }
                                    AudioFormat.ENCODING_PCM_FLOAT -> ShortArray(sourceChunkCount).also { samples ->
                                        val floats = sampleBytes.asFloatBuffer().apply { position(sourceOffset) }
                                        repeat(sourceChunkCount) { index ->
                                            if ((index and 0x1fff) == 0) checkActive()
                                            samples[index] = Pcm16SampleConverter.floatToPcm16(floats.get())
                                        }
                                    }
                                    else -> throw IllegalStateException("音频解码器返回了不支持的 PCM 编码：${pcmEncoding}")
                                }

                                val monoChunk = if (sourceChannels == 1) {
                                    decodedChunk
                                } else {
                                    PcmChannelMixer.downmixChunk(decodedChunk, sourceChannels, checkActive)
                                }
                                // Keep the resampler call bounded too; with supported decoder sample
                                // rates the normal chunk already fits, but retain this guard if its size changes.
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
                                    writePcm16Le(output, converted, pcmWriteBuffer, checkActive)
                                    outputBytes += converted.size.toLong() * 2L
                                    outputSamples += converted.size
                                    monoOffset += chunkCount
                                }
                                sourceOffset += sourceChunkCount
                            }
                            decodedSourceSamples += take
                        }
                        val reachedEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                            decodedSourceSamples >= maxSourceSamples
                        codec.releaseOutputBuffer(outBufferIndex, false)
                        if (info.size > 0 && mediaDurationUs > 0L) {
                            val nextPercent = MediaTimelineProgressPolicy.nextPercent(
                                positionUs = info.presentationTimeUs,
                                durationUs = mediaDurationUs,
                                previousPercent = lastProgressPercent,
                            )
                            if (nextPercent != null) {
                                lastProgressPercent = nextPercent
                                onProgressPercent(nextPercent)
                            }
                        }
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
                    } else if (outBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // Buffer-layout notification only; it is not audio data progress.
                    }

                    if (loopMadeProgress) {
                        lastCodecProgressAtMs = android.os.SystemClock.elapsedRealtime()
                    } else if (
                        android.os.SystemClock.elapsedRealtime() - lastCodecProgressAtMs > MAX_CODEC_STALL_MS
                    ) {
                        throw IllegalStateException(
                            "音频解码器连续 60 秒没有输出进展；媒体可能损坏或当前解码器不兼容，请换一个音频源重试"
                        )
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
                writePcm16Le(output, tail, pcmWriteBuffer, checkActive)
                outputBytes += tail.size.toLong() * 2L
                outputSamples += tail.size
                output.flush()
            }

            if (outputSamples <= 0L) throw IllegalStateException("音频轨没有可解码的 PCM 数据")
            onProgressPercent(100)
            success = true
            return PcmFile(destination, outputSamples, 16_000)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            if (!success) runCatching { destination.delete() }
        }
    }

    internal fun writePcm16Le(
        output: BufferedOutputStream,
        samples: ShortArray,
        bytes: ByteArray,
        checkActive: () -> Unit = {},
    ) {
        if (samples.isEmpty()) return
        require(bytes.size >= 2 && bytes.size % 2 == 0) { "PCM 输出缓冲区必须按 16 位采样对齐" }
        checkActive()
        // Convert and write small chunks instead of duplicating an entire codec buffer.
        // Reuse the caller-owned scratch buffer across all decoded chunks.
        val samplesPerChunk = bytes.size / 2
        var sampleOffset = 0
        while (sampleOffset < samples.size) {
            checkActive()
            val count = minOf(samplesPerChunk, samples.size - sampleOffset)
            var byteIndex = 0
            var index = sampleOffset
            val end = sampleOffset + count
            while (index < end) {
                val value = samples[index++].toInt()
                bytes[byteIndex] = value.toByte()
                bytes[byteIndex + 1] = (value shr 8).toByte()
                byteIndex += 2
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
