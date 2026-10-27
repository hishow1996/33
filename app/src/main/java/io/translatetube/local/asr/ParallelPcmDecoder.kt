package io.translatetube.local.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import io.translatetube.local.util.AppLogStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/**
 * 本地长音频的分段并行解码：把音轨按时间切成几段，几个线程同时解，最后顺序拼成 16 kHz 单声道 PCM。
 *
 * 只在"有把握"的情况下启用（本地文件/本地 Uri、AAC 或 Opus、时长够长、可随机跳转），并且每一步都有校验：
 * 切点对齐重采样网格、时间戳连续、相邻两段在拼缝处的样本互相吻合、总长度与标称时长吻合。
 * 任何一项对不上都返回 null，由调用方退回原来的顺序解码——所以最坏情况只是没提速，不会出错音频。
 * 取消（[CancellationException]）照常向上抛。
 */
internal object ParallelPcmDecoder {
    private const val LOG_TAG = "AudioDecode"
    /** 设置页开关（默认关闭）。 */
    const val PREF_KEY = "parallel_decode_enabled"
    /** 支持并行的编码：帧结构固定、时间戳可靠、跳转后靠预滚就能收敛。 */
    private val SUPPORTED_MIMES = setOf("audio/mp4a-latm", "audio/opus")
    /** 跳转后实际落点比请求的时间早太多，说明容器没有可用的索引，跳转等于从头读，并行没有意义。 */
    private const val MAX_SEEK_SLACK_US = 3_000_000L
    private const val MEASURE_TIMEOUT_MS = 10_000L
    private const val MAX_CODEC_STALL_MS = 60_000L
    private const val BLOCKING_WAIT_US = 10_000L
    private const val MAX_INPUT_BUFFERS_PER_PASS = 16
    /** 单个解码缓冲区的帧数上限；超过说明解码器行为异常，放弃并行。 */
    private const val MAX_BUFFER_FRAMES = 1 shl 20

    /** 某种编码校验失败过一次，本次进程里就不再对它重复尝试（避免每个视频都白跑一遍）。 */
    private val disabledMimes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private class SegmentResult(
        val file: File,
        val outputSamples: Long,
        val head: ShortArray,
        val tail: ShortArray,
    )

    private class Probe(
        val trackIndex: Int,
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long,
        val firstPtsUs: Long,
        val mime: String,
    )

    /** @return 解码结果；不适用或校验未通过返回 null（调用方改走顺序解码）。 */
    fun tryDecode(
        ctx: Context,
        src: Uri?,
        file: File?,
        checkActive: () -> Unit,
        onProgressPercent: (Int) -> Unit,
    ): AudioExtract.PcmFile? {
        if (!ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)) return null
        val localUri = src != null && (src.scheme == "content" || src.scheme == "file")
        if (file == null && !localUri) return null

        val probe: Probe? = try {
            probeSource(ctx, src, file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (probe == null || probe.mime in disabledMimes) return null

        // 实测开头被解码器丢掉的样本数（AAC 通常为 0，Opus 有 pre-skip），用来把"时间戳位置"
        // 换算成"顺序解码的样本序号"，保证并行结果与顺序解码对齐。
        val leadingDiscard: Long? = try {
            measureLeadingDiscard(ctx, src, file, probe, checkActive)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (leadingDiscard == null) return null

        val cores = Runtime.getRuntime().availableProcessors()
        val workers = ParallelDecodePlan.workerCount(probe.durationMs, (cores - 1).coerceAtLeast(1))
        if (workers < 2) return null
        val segments = ParallelDecodePlan.plan(probe.sampleRate, probe.durationMs, workers) ?: return null
        // 预计输出就超过临时文件上限：交给顺序解码给出原来的报错。
        if (probe.durationMs * ParallelDecodePlan.TARGET_RATE / 1000L * 2L > PcmTempFilePolicy.MAX_PCM_BYTES) return null

        val startedAt = SystemClock.elapsedRealtime()
        val abort = AtomicBoolean(false)
        val progress = Array(segments.size) { AtomicLong(0L) }
        val executor = Executors.newFixedThreadPool(segments.size) { runnable ->
            Thread(runnable, "pcm-decode").apply { isDaemon = true }
        }
        val futures = ArrayList<Future<SegmentResult>>(segments.size)
        var finalFile: File? = null
        var success = false
        try {
            val workerGuard: () -> Unit = {
                if (abort.get()) throw CancellationException("并行解码已中止")
                checkActive()
            }
            for (segment in segments) {
                futures += executor.submit(
                    Callable {
                        decodeSegment(ctx, src, file, probe, leadingDiscard, segment, progress[segment.index], workerGuard)
                    },
                )
            }

            val totalEstimate = ParallelDecodePlan.estimatedSourceSamples(probe.sampleRate, probe.durationMs)
            var lastPercent = -1
            while (true) {
                checkActive()
                var allDone = true
                for (future in futures) {
                    if (!future.isDone) {
                        allDone = false
                        continue
                    }
                    try {
                        future.get()
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                }
                var done = 0L
                for (counter in progress) done += counter.get()
                MediaTimelineProgressPolicy.nextSamplePercent(done, totalEstimate, lastPercent)?.let {
                    lastPercent = it
                    onProgressPercent(it)
                }
                if (allDone) break
                try {
                    Thread.sleep(120L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("并行解码被中断")
                }
            }

            val results = futures.map { it.get() }
            // 拼缝校验：前一段多解的 1.5 秒必须和后一段开头逐点对齐（严格，量不准一律不通过）。
            val seamNotes = ArrayList<String>()
            for (i in 1 until results.size) {
                val check = ParallelDecodePlan.compareSeam(results[i - 1].tail, results[i].head)
                seamNotes += "第${i}缝：${check.describe()}"
                if (check.verdict != ParallelDecodePlan.SeamVerdict.ALIGNED) {
                    throw IllegalStateException("第 $i 段与前一段拼缝未通过对齐校验：${check.describe()}")
                }
            }
            for ((i, result) in results.withIndex()) {
                val expected = segments[i].outputCount
                if (expected >= 0L && result.outputSamples != expected) {
                    throw IllegalStateException("第 $i 段输出长度不符")
                }
                if (!PcmTempFilePolicy.hasCompletePcm16Payload(result.file, result.outputSamples)) {
                    throw IllegalStateException("第 $i 段临时文件不完整")
                }
            }
            val totalSamples = results.sumOf { it.outputSamples }
            if (!ParallelDecodePlan.totalLooksRight(totalSamples, probe.durationMs)) {
                throw IllegalStateException("拼出的总长度与媒体标称时长不符")
            }

            val destination = File.createTempFile(PcmTempFilePolicy.PREFIX, PcmTempFilePolicy.SUFFIX, ctx.cacheDir)
            finalFile = destination
            FileOutputStream(destination).channel.use { out ->
                for (result in results) {
                    checkActive()
                    FileInputStream(result.file).channel.use { input ->
                        val size = input.size()
                        var position = 0L
                        while (position < size) {
                            checkActive()
                            position += input.transferTo(position, size - position, out)
                        }
                    }
                }
            }
            if (!PcmTempFilePolicy.hasCompletePcm16Payload(destination, totalSamples)) {
                throw IllegalStateException("拼接后的音频文件长度不符")
            }
            onProgressPercent(100)
            success = true
            AppLogStore.info(
                ctx,
                LOG_TAG,
                "本地音频分段并行解码完成",
                diagnosticContext = mapOf(
                    "线程数" to segments.size.toString(),
                    "源采样率" to probe.sampleRate.toString(),
                    "声道数" to probe.channels.toString(),
                    "时长ms" to probe.durationMs.toString(),
                    "拼缝校验" to seamNotes.joinToString("；"),
                    "耗时ms" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                ),
            )
            return AudioExtract.PcmFile(destination, totalSamples, ParallelDecodePlan.TARGET_RATE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            disabledMimes += probe.mime
            AppLogStore.info(
                ctx,
                LOG_TAG,
                "分段并行解码未采用，改用顺序解码",
                details = failure.message ?: failure.javaClass.simpleName,
            )
            return null
        } finally {
            abort.set(true)
            executor.shutdownNow()
            runCatching { executor.awaitTermination(3, TimeUnit.SECONDS) }
            // 各段的临时文件：成功时已拼进最终文件，失败/取消时一律清掉。
            for (future in futures) {
                if (future.isDone && !future.isCancelled) {
                    runCatching { future.get().file.delete() }
                }
            }
            if (!success) finalFile?.let { runCatching { it.delete() } }
        }
    }

    private fun openSource(extractor: MediaExtractor, ctx: Context, src: Uri?, file: File?) {
        when {
            file != null -> extractor.setDataSource(file.absolutePath)
            src != null -> extractor.setDataSource(ctx, src, null)
            else -> throw IllegalStateException("无音频源")
        }
    }

    private fun probeSource(ctx: Context, src: Uri?, file: File?): Probe? {
        val extractor = MediaExtractor()
        try {
            openSource(extractor, ctx, src, file)
            val candidates = ArrayList<AudioTrackSelectionPolicy.Candidate>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val channelCount = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
                    val isDefault = runCatching {
                        format.containsKey("is-default") && format.getInteger("is-default") != 0
                    }.getOrDefault(false)
                    candidates += AudioTrackSelectionPolicy.Candidate(i, isDefault, channelCount)
                }
            }
            val trackIndex = AudioTrackSelectionPolicy.choose(candidates) ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (mime !in SUPPORTED_MIMES) return null
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return null
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            if (durationUs <= 0L) return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sampleRate !in ParallelDecodePlan.MIN_SOURCE_RATE..ParallelDecodePlan.MAX_SOURCE_RATE) return null
            if (channels !in 1..8) return null
            extractor.selectTrack(trackIndex)
            val firstPts = extractor.sampleTime
            if (firstPts < 0L) return null
            return Probe(trackIndex, sampleRate, channels, durationUs / 1000L, firstPts, mime)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * 从流开头解出前两个非空输出缓冲区，实测解码器开头丢掉的样本数，见
     * [ParallelDecodePlan.leadingDiscardFrames]。拿不到或数值异常返回 null（放弃并行）。
     */
    private fun measureLeadingDiscard(
        ctx: Context,
        src: Uri?,
        file: File?,
        probe: Probe,
        guard: () -> Unit,
    ): Long? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            openSource(extractor, ctx, src, file)
            extractor.selectTrack(probe.trackIndex)
            val format = extractor.getTrackFormat(probe.trackIndex)
            val decoder = MediaCodec.createDecoderByType(probe.mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var channels = probe.channels
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val info = MediaCodec.BufferInfo()
            var firstFrames = -1L
            var sawInputEos = false
            val deadline = SystemClock.elapsedRealtime() + MEASURE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                guard()
                var queued = 0
                while (!sawInputEos && queued < MAX_INPUT_BUFFERS_PER_PASS) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val inBuffer = decoder.getInputBuffer(inIndex) ?: return null
                    val size = extractor.readSampleData(inBuffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    queued++
                }
                val outIndex = decoder.dequeueOutputBuffer(info, BLOCKING_WAIT_US)
                if (outIndex >= 0) {
                    var result: Long? = null
                    var finished = false
                    try {
                        if (info.size > 0) {
                            val bytesPerSample = when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                else -> return null
                            }
                            if (channels !in 1..8 || info.size % (bytesPerSample * channels) != 0) return null
                            val frames = (info.size / (bytesPerSample * channels)).toLong()
                            if (firstFrames < 0L) {
                                firstFrames = frames
                            } else {
                                val ptsFrame = ParallelDecodePlan.frameIndexAtUs(
                                    info.presentationTimeUs, probe.firstPtsUs, probe.sampleRate,
                                )
                                result = ParallelDecodePlan.leadingDiscardFrames(ptsFrame, firstFrames, probe.sampleRate)
                                finished = true
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finished = true
                    } finally {
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    if (finished) return result
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 解码一段并写成独立的 16 kHz 单声道 PCM 临时文件。 */
    private fun decodeSegment(
        ctx: Context,
        src: Uri?,
        file: File?,
        probe: Probe,
        leadingDiscard: Long,
        segment: ParallelDecodePlan.Segment,
        progress: AtomicLong,
        guard: () -> Unit,
    ): SegmentResult {
        val rate = probe.sampleRate
        val seam = ParallelDecodePlan.seamSamples(rate)
        val keepFrom = segment.sourceStart
        val keepTo = if (segment.isLast) Long.MAX_VALUE else segment.sourceEnd + seam

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val partFile = File.createTempFile("translate-pcm-part-", PcmTempFilePolicy.SUFFIX, ctx.cacheDir)
        var ok = false
        try {
            openSource(extractor, ctx, src, file)
            extractor.selectTrack(probe.trackIndex)
            // 每个线程自己取一份格式，避免多线程共用同一个可变的 MediaFormat。
            val format = extractor.getTrackFormat(probe.trackIndex)
            if (!segment.isFirst) {
                val targetUs = ParallelDecodePlan.timeUsForFrame(keepFrom, rate, probe.firstPtsUs) -
                    ParallelDecodePlan.PREROLL_US
                val seekUs = maxOf(probe.firstPtsUs, targetUs)
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val landedUs = extractor.sampleTime
                if (landedUs < 0L) throw IllegalStateException("无法跳转到第 ${segment.index} 段")
                if (seekUs - landedUs > MAX_SEEK_SLACK_US) {
                    throw IllegalStateException("容器跳转不精确（第 ${segment.index} 段落点偏早 ${(seekUs - landedUs) / 1000} ms）")
                }
            }
            val decoder = MediaCodec.createDecoderByType(probe.mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var channels = probe.channels
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val resampler = Pcm16StreamingResampler(rate, ParallelDecodePlan.TARGET_RATE)
            val head = ShortArray(seam)
            var headFilled = 0
            val tail = ShortArray(seam)
            var tailFilled = 0
            var written = 0L
            var nextFrame = -1L
            var nonEmptyBuffers = 0
            val ptsTolerance = ParallelDecodePlan.ptsTolerance(rate)
            var done = false
            var sawInputEos = false
            var sawOutputEos = false
            val info = MediaCodec.BufferInfo()
            val scratch = ByteArray(16 * 1024)

            BufferedOutputStream(FileOutputStream(partFile), 64 * 1024).use { output ->
                fun emit(samples: ShortArray) {
                    val take = if (segment.isLast) {
                        samples.size
                    } else {
                        minOf(samples.size.toLong(), segment.outputCount - written).toInt()
                    }
                    if (take <= 0) return
                    if ((written + take) * 2L > PcmTempFilePolicy.MAX_PCM_BYTES) {
                        throw IllegalStateException("解码后的临时音频超过 512 MB 安全上限")
                    }
                    AudioExtract.writePcm16Le(output, if (take == samples.size) samples else samples.copyOf(take), scratch, guard)
                    written += take
                }

                fun consume(mono: ShortArray, absoluteStart: Long) {
                    if (!segment.isFirst) {
                        var i = 0
                        while (i < mono.size && headFilled < seam) head[headFilled++] = mono[i++]
                    }
                    if (!segment.isLast) {
                        val tailStart = segment.sourceEnd
                        val from = maxOf(absoluteStart, tailStart)
                        val to = minOf(absoluteStart + mono.size, tailStart + seam)
                        if (to > from) {
                            var a = from
                            while (a < to) {
                                tail[(a - tailStart).toInt()] = mono[(a - absoluteStart).toInt()]
                                a++
                            }
                            tailFilled += (to - from).toInt()
                        }
                    }
                    emit(resampler.process(mono, guard))
                    progress.set(absoluteStart + mono.size - keepFrom)
                }

                var lastProgressAt = SystemClock.elapsedRealtime()
                var outputWaitUs = BLOCKING_WAIT_US
                while (!sawOutputEos && !done) {
                    guard()
                    var progressed = false
                    var queued = 0
                    while (!sawInputEos && queued < MAX_INPUT_BUFFERS_PER_PASS) {
                        val inIndex = decoder.dequeueInputBuffer(0)
                        if (inIndex < 0) break
                        val inBuffer = decoder.getInputBuffer(inIndex)
                            ?: throw IllegalStateException("音频解码器没有输入缓冲区")
                        val size = extractor.readSampleData(inBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                            progressed = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            val hasNext = extractor.advance()
                            progressed = progressed || CodecProgressPolicy.inputMakesProgress(size, hasNext)
                        }
                        queued++
                    }

                    val outIndex = decoder.dequeueOutputBuffer(info, outputWaitUs)
                    outputWaitUs = if (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        0L
                    } else {
                        BLOCKING_WAIT_US
                    }
                    if (outIndex >= 0) {
                        progressed = progressed || CodecProgressPolicy.outputMakesProgress(
                            info.size,
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0,
                        )
                        try {
                            if (info.size > 0) {
                                val bytesPerSample = when (encoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> 2
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    else -> throw IllegalStateException("音频解码器返回了不支持的 PCM 编码：$encoding")
                                }
                                val outBuffer = decoder.getOutputBuffer(outIndex)
                                    ?: throw IllegalStateException("音频解码器没有输出缓冲区")
                                val endOffset = info.offset.toLong() + info.size.toLong()
                                if (!PcmFrameLayout.isValidBuffer(info.offset, info.size, bytesPerSample, channels) ||
                                    endOffset > outBuffer.capacity().toLong()
                                ) {
                                    throw IllegalStateException("音频解码缓冲区未按 PCM 声道帧对齐")
                                }
                                val frames = info.size / (bytesPerSample * channels)
                                if (frames > MAX_BUFFER_FRAMES) throw IllegalStateException("音频解码缓冲区异常过大")

                                // 时间戳换算成"顺序解码的样本序号"（减去解码器开头丢掉的样本）。
                                val ptsFrame = ParallelDecodePlan.frameIndexAtUs(
                                    info.presentationTimeUs, probe.firstPtsUs, rate,
                                ) - leadingDiscard
                                nonEmptyBuffers++
                                // 跳转后的第一个输出缓冲区可能被解码器裁掉开头（如 Opus 的 pre-skip），
                                // 只用来预热，不用它定位；从第二个缓冲区开始以时间戳为锚点。
                                val warmUpOnly = !segment.isFirst && nonEmptyBuffers == 1
                                if (!warmUpOnly) {
                                    val bufferStart = when {
                                        segment.isFirst && nextFrame < 0L -> 0L
                                        nextFrame < 0L -> {
                                            // 预滚没有覆盖到切点：这一段无法保证对齐。
                                            if (ptsFrame > keepFrom) throw IllegalStateException("第 ${segment.index} 段预滚不足")
                                            ptsFrame
                                        }
                                        else -> {
                                            if (!ParallelDecodePlan.isContiguous(nextFrame, ptsFrame, ptsTolerance)) {
                                                throw IllegalStateException("解码输出时间戳不连续")
                                            }
                                            nextFrame
                                        }
                                    }
                                    nextFrame = bufferStart + frames
                                    val slice = ParallelDecodePlan.sliceOfBuffer(bufferStart, frames, keepFrom, keepTo)
                                    if (slice != null) {
                                        val pcm = outBuffer.duplicate()
                                        pcm.clear()
                                        val sliceStartByte =
                                            info.offset + slice.skipFrames * channels * bytesPerSample
                                        pcm.position(sliceStartByte)
                                        pcm.limit(sliceStartByte + slice.takeFrames * channels * bytesPerSample)
                                        val sampleBytes = pcm.slice().order(ByteOrder.LITTLE_ENDIAN)
                                        val total = slice.takeFrames * channels
                                        val interleaved = ShortArray(total)
                                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                            val floats = sampleBytes.asFloatBuffer()
                                            for (i in 0 until total) {
                                                interleaved[i] = Pcm16SampleConverter.floatToPcm16(floats.get())
                                            }
                                        } else {
                                            sampleBytes.asShortBuffer().get(interleaved, 0, total)
                                        }
                                        val mono = if (channels == 1) {
                                            interleaved
                                        } else {
                                            PcmChannelMixer.downmixChunk(interleaved, channels, guard)
                                        }
                                        consume(mono, bufferStart + slice.skipFrames)
                                    }
                                    if (nextFrame >= keepTo) done = true
                                }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        } finally {
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = decoder.outputFormat
                        val newRate = if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else rate
                        if (newRate != rate) throw IllegalStateException("解码器实际采样率与容器声明不一致")
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (channels !in 1..8) throw IllegalStateException("音频声道数不受支持：$channels")
                    }

                    if (progressed) {
                        lastProgressAt = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - lastProgressAt > MAX_CODEC_STALL_MS) {
                        throw IllegalStateException("音频解码器连续 60 秒没有输出进展")
                    }
                }

                if (nextFrame < 0L) throw IllegalStateException("第 ${segment.index} 段没有解出任何数据")
                if (segment.isLast) {
                    emit(resampler.finish(guard))
                    if (written <= 0L) throw IllegalStateException("最后一段没有可用的 PCM 数据")
                } else if (written != segment.outputCount || tailFilled != seam) {
                    throw IllegalStateException("第 ${segment.index} 段数据不足")
                }
                if (!segment.isFirst && headFilled != seam) {
                    throw IllegalStateException("第 ${segment.index} 段开头数据不足")
                }
                output.flush()
            }
            ok = true
            return SegmentResult(partFile, written, head, tail)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            if (!ok) runCatching { partFile.delete() }
        }
    }
}
