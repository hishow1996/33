package io.translatetube.local.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * SenseVoiceSmall INT8 running locally through the app's existing sherpa-onnx Android runtime.
 * The app's VAD supplies stable media timestamps; recognized text stays within each VAD segment.
 */
class SenseVoiceEngine(ctx: Context, private val numThreads: Int = 2) : LocalAsrEngine {
    private val recognizer: OfflineRecognizer
    @Volatile private var closed = false

    @Volatile
    override var lastLang: String = ""
        private set

    /** 按音频时长投票得出整段视频的语言，避免被结尾一句很短的话认错。 */
    private val languageVote = AsrLanguageVotePolicy()

    @Volatile
    override var lastEmotion: String = ""
        private set

    @Volatile
    override var lastEvent: String = ""
        private set

    init {
        require(numThreads in 1..4) { "本地转写线程数必须在 1 到 4 之间" }
        check(io.translatetube.local.data.ModelFiles.senseVoiceCoreReady(ctx)) {
            "本地 SenseVoice 模型不完整：需要 model.int8.onnx 和 tokens.txt；请在设置 → 转写引擎中重新下载模型。"
        }
        val dir = io.translatetube.local.data.ModelFiles.senseVoiceDir(ctx).absolutePath + "/"
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "${dir}model.int8.onnx",
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = "${dir}tokens.txt",
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    private fun decodeResult(samples: FloatArray): com.k2fsa.sherpa.onnx.OfflineRecognizerResult {
        check(!closed) { "SenseVoice 识别器已关闭" }
        require(samples.isNotEmpty()) { "没有可供 SenseVoice 转写的音频样本" }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate = 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).also { result ->
                result.emotion.takeIf { it.isNotBlank() }?.let { lastEmotion = it }
                result.event.takeIf { it.isNotBlank() }?.let { lastEvent = it }
            }
        } finally {
            runCatching { stream.release() }
        }
    }

    @Synchronized
    override fun transcribeBySegments(
        pcmFile: AudioExtract.PcmFile,
        segs: List<Pair<Long, Long>>,
        checkActive: () -> Unit,
        sharedPcmReader: AudioExtract.PcmFile.Reader?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Triple<Long, Long, String>> {
        check(!closed) { "SenseVoice 识别器已关闭" }
        require(pcmFile.sampleRate == 16_000) { "SenseVoice 只支持 16kHz PCM 输入" }
        val durationMs = pcmFile.durationMs
        val sampleRate = pcmFile.sampleRate.toLong()
        val output = mutableListOf<Triple<Long, Long, String>>()
        val batchStartedMs = android.os.SystemClock.elapsedRealtime()
        var audioMs = 0L
        var sampleReadMs = 0L
        var inferenceMs = 0L
        var decodedSegments = 0
        val ownsPcmReader = sharedPcmReader == null
        val pcmReader = sharedPcmReader ?: pcmFile.openReader(checkActive)
        try {
            segs.forEachIndexed { index, (startMs, endMs) ->
                checkActive()
                val safeStartMs = startMs.coerceIn(0L, durationMs)
                val safeEndMs = endMs.coerceIn(safeStartMs, durationMs)
                require(safeEndMs - safeStartMs <= VadSegmentMergePolicy.MAX_MERGED_SEGMENT_MS) {
                    "单条 SenseVoice 语音段超过 25 秒安全上限，请先重新分段"
                }
                val startSample = safeStartMs * sampleRate / 1000L
                val endSample = (safeEndMs * sampleRate / 1000L).coerceAtMost(pcmFile.sampleCount)
                if (endSample > startSample) {
                    val count = endSample - startSample
                    require(count <= Int.MAX_VALUE.toLong()) { "单条语音段过长" }
                    val readStartedMs = android.os.SystemClock.elapsedRealtime()
                    val samples = pcmReader.readFloatSamples(startSample, count.toInt())
                    sampleReadMs += android.os.SystemClock.elapsedRealtime() - readStartedMs
                    val decodeStartedMs = android.os.SystemClock.elapsedRealtime()
                    val result = decodeResult(samples)
                    inferenceMs += android.os.SystemClock.elapsedRealtime() - decodeStartedMs
                    audioMs += safeEndMs - safeStartMs
                    decodedSegments++
                    val text = result.text.trim()
                    checkActive()
                    if (text.isNotEmpty()) {
                        languageVote.add(result.lang, safeEndMs - safeStartMs)
                        languageVote.best().takeIf { it.isNotEmpty() }?.let { lastLang = it }
                        // SenseVoice 没有词级时间戳：一个语音段（最长 25 秒）按标点拆成短句行，
                        // 并清掉幻觉/循环输出，字幕和配音才不会是一整行几十秒的长句。
                        output.addAll(
                            AsrCueSplitPolicy.finishSegmentRows(
                                listOf(Triple(safeStartMs, safeEndMs, text)),
                            ),
                        )
                    }
                }
                onProgress(index + 1, segs.size)
            }
        } finally {
            if (ownsPcmReader) pcmReader.close()
        }
        LocalAsrPerformanceLogger.log(
            modelId = "sensevoice",
            numThreads = numThreads,
            decodedSegments = decodedSegments,
            audioMs = audioMs,
            sampleReadMs = sampleReadMs,
            inferenceMs = inferenceMs,
            batchMs = android.os.SystemClock.elapsedRealtime() - batchStartedMs,
        )
        return output
    }

    @Synchronized
    override fun clearDetectedLanguage() {
        check(!closed) { "SenseVoice 识别器已关闭" }
        languageVote.clear()
        lastLang = ""
        lastEmotion = ""
        lastEvent = ""
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        recognizer.release()
    }
}
