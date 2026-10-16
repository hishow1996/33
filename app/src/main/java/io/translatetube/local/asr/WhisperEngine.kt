package io.translatetube.local.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult

/** Whisper base 多语言本地转写（sherpa-onnx） */
class WhisperEngine(ctx: Context) : AutoCloseable {
    private val recognizer: OfflineRecognizer
    @Volatile private var closed = false
    /** 最近一次转写检测到的语言码（如 "en"/"ja"） */
    @Volatile var lastLang: String = ""
        private set

    init {
        val m = io.translatetube.local.data.ModelFiles.asrDir(ctx).absolutePath + "/"
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "${m}base-encoder.int8.onnx",
                    decoder = "${m}base-decoder.int8.onnx",
                    // DTW token alignment exposes word/subword timing where the model supports attention outputs.
                    enableTokenTimestamps = true,
                ),
                tokens = "${m}base-tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        // assets=null → 文件系统模式（方案 B）
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    /** 一次转写整段 PCM（16k mono float），自动检测语言 */
    private fun decodeResult(pcm: FloatArray): OfflineRecognizerResult {
        check(!closed) { "Whisper 识别器已关闭" }
        require(pcm.isNotEmpty()) { "没有可供 Whisper 转写的音频样本" }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(pcm, sampleRate = 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).also { result ->
                result.lang?.takeIf { it.isNotBlank() }?.let { lastLang = it }
            }
        } finally {
            // sherpa streams own native memory; always release even when decode/result throws.
            runCatching { stream.release() }
        }
    }

    @Synchronized
    fun transcribe(pcm: FloatArray): String = decodeResult(pcm).text

    /** 长音频分段转写：30s 窗口 + 1s 重叠，返回带时间轴文本 */
    @Synchronized
    fun transcribeSegmented(
        pcm: FloatArray,
        onProgress: (doneSec: Long, totalSec: Long) -> Unit = { _, _ -> },
    ): List<Pair<Long, String>> {
        check(!closed) { "Whisper 识别器已关闭" }
        val sr = 16000
        val window = 30 * sr
        val hop = 29 * sr
        val out = mutableListOf<Pair<Long, String>>()
        var start = 0
        val totalSec = WhisperProgressPolicy.secondsCeiling(pcm.size, sr)
        while (start < pcm.size) {
            val end = (start + window).coerceAtMost(pcm.size)
            val seg = pcm.copyOfRange(start, end)
            val text = transcribe(seg).trim()
            if (text.isNotEmpty()) out += (start / sr).toLong() to text
            onProgress(WhisperProgressPolicy.secondsCeiling(end, sr), totalSec)
            if (end >= pcm.size) break
            start += hop
        }
        return out
    }

    /** Reset language inference before a new video's ASR task to prevent cross-video leakage. */
    @Synchronized
    fun clearDetectedLanguage() {
        check(!closed) { "Whisper 识别器已关闭" }
        lastLang = ""
    }

    /** VAD 分段精确转写：段起点/终点来自 VAD，行级时间轴 */
    @Synchronized
    fun transcribeBySegments(
        pcm: FloatArray,
        segs: List<Pair<Long, Long>>,
        checkActive: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Triple<Long, Long, String>> {
        check(!closed) { "Whisper 识别器已关闭" }
        val sr = 16000
        val durationMs = pcm.size.toLong() * 1000L / sr
        val out = mutableListOf<Triple<Long, Long, String>>()
        segs.forEachIndexed { i, (a, b) ->
            // Cancel between native calls. The current sherpa decode itself is synchronous and
            // cannot be preempted safely while native memory is in use.
            checkActive()
            val startMs = a.coerceIn(0L, durationMs)
            val endMs = b.coerceIn(startMs, durationMs)
            require(endMs - startMs <= VadSegmentMergePolicy.MAX_MERGED_SEGMENT_MS) {
                "单条 Whisper 语音段超过 25 秒安全上限，请先重新分段"
            }
            val s0 = (startMs * sr / 1000L).toInt()
            val s1 = (endMs * sr / 1000L).toInt()
            if (s1 > s0) {
                val result = decodeResult(pcm.copyOfRange(s0, s1))
                val text = result.text.trim()
                checkActive()
                if (text.isNotEmpty()) {
                    val timedRows = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                        tokens = result.tokens.toList(),
                        timestampsSeconds = result.timestamps.toList(),
                        durationsSeconds = result.durations.toList(),
                        segmentStartMs = startMs,
                        segmentEndMs = endMs,
                        expectedText = text,
                    )
                    if (timedRows.isNotEmpty()) out.addAll(timedRows)
                    else out += Triple(startMs, endMs, text)
                }
            }
            onProgress(i + 1, segs.size)
        }
        return out
    }


    /** File-backed VAD transcription: materialize only the current speech segment. */
    @Synchronized
    fun transcribeBySegments(
        pcmFile: AudioExtract.PcmFile,
        segs: List<Pair<Long, Long>>,
        checkActive: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Triple<Long, Long, String>> {
        check(!closed) { "Whisper 识别器已关闭" }
        val sr = pcmFile.sampleRate
        require(sr == 16_000) { "Whisper 只支持 16kHz PCM 输入" }
        val durationMs = pcmFile.durationMs
        val out = mutableListOf<Triple<Long, Long, String>>()
        segs.forEachIndexed { index, (startMs, endMs) ->
            checkActive()
            val safeStartMs = startMs.coerceIn(0L, durationMs)
            val safeEndMs = endMs.coerceIn(safeStartMs, durationMs)
            require(safeEndMs - safeStartMs <= VadSegmentMergePolicy.MAX_MERGED_SEGMENT_MS) {
                "单条 Whisper 语音段超过 25 秒安全上限，请先重新分段"
            }
            val startSample = safeStartMs * sr / 1000L
            val endSample = (safeEndMs * sr / 1000L).coerceAtMost(pcmFile.sampleCount)
            if (endSample > startSample) {
                val sampleCount = endSample - startSample
                require(sampleCount <= Int.MAX_VALUE.toLong()) { "单条语音段过长" }
                val samples = pcmFile.readFloatSamples(startSample, sampleCount.toInt(), checkActive)
                val result = decodeResult(samples)
                val text = result.text.trim()
                checkActive()
                if (text.isNotEmpty()) {
                    val timedRows = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                        tokens = result.tokens.toList(),
                        timestampsSeconds = result.timestamps.toList(),
                        durationsSeconds = result.durations.toList(),
                        segmentStartMs = safeStartMs,
                        segmentEndMs = safeEndMs,
                        expectedText = text,
                    )
                    if (timedRows.isNotEmpty()) out.addAll(timedRows)
                    else out += Triple(safeStartMs, safeEndMs, text)
                }
            }
            onProgress(index + 1, segs.size)
        }
        return out
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        recognizer.release()
    }
}
