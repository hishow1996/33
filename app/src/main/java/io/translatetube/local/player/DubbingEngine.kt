package io.translatetube.local.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.media.PlaybackParams
import io.translatetube.local.subs.SubLine
import io.translatetube.local.tts.TtsEngine
import io.translatetube.local.tts.TimelineTtsPlayback
import io.translatetube.local.tts.TtsInputLimitException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AppLogStore performs synchronous file IO. Keep diagnostic writes off Media3/audio callbacks,
 * especially when the mixer is feeding a cue at the exact subtitle timestamp.
 */
private object DubbingLogDispatcher {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dubbing-log-writer").apply { isDaemon = true }
    }

    fun enqueue(action: () -> Unit) {
        runCatching {
            executor.execute { runCatching(action) }
        }
    }
}

/**
 * 行级时间轴配音引擎：
 * Kokoro/Piper 等引擎在后台逐行合成并缓存 PCM，由 AudioTrack 按时间轴切行；
 * 系统 TTS 则在对应字幕时间点直接调用 Android 朗读服务，不再预合成 PCM。
 */
private class DubbingPcmLimitException(message: String) : IllegalStateException(message)
private class DubbingChunkBoundaryTimeoutException(message: String = "等待配音分段结束超时") : IllegalStateException(message)

private data class DubbingCacheInspection(
    val hasAudio: Boolean?,
    val stamp: DubbingCacheFileStamp?,
)

class DubbingEngine(
    ctx: Context,
    private val videoKey: String,
    private val scope: CoroutineScope,
    /** 混流输出总线（与 ExoPlayer 音频渲染管线同源时钟）；合成层把 PCM 按媒体时间挂载。 */
    private val outputBus: DubbingTrackBus,
) {
    private val appContext = ctx.applicationContext

    private fun logDubbingInfo(
        message: String,
        diagnosticContext: Map<String, String> = emptyMap(),
        tag: String = "DubbingEngine",
    ) {
        DubbingLogDispatcher.enqueue {
            io.translatetube.local.util.AppLogStore.info(
                appContext,
                tag,
                message,
                diagnosticContext = diagnosticContext,
            )
        }
    }

    private fun logDubbingError(
        message: String,
        failure: Throwable? = null,
        diagnosticContext: Map<String, String> = emptyMap(),
    ) {
        DubbingLogDispatcher.enqueue {
            io.translatetube.local.util.AppLogStore.error(
                appContext,
                "DubbingEngine",
                message,
                failure,
                diagnosticContext = diagnosticContext,
            )
        }
    }
    // videoKey may contain a content:// URI with slashes. Hash it to keep all cache files
    // inside one bounded, collision-resistant directory instead of creating nested path segments.
    private val cacheRootDir = File(ctx.cacheDir, "dub").apply { mkdirs() }
    private val cacheDir = File(
        cacheRootDir,
        DubbingCachePath.directoryName(videoKey),
    ).apply { mkdirs() }
    private val activityManager =
        ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    private val pcmMemoryBudgetBytes = DubbingPcmMemoryPolicy.maxBytes(
        isLowRamDevice = activityManager?.isLowRamDevice == true,
        memoryClassMb = activityManager?.memoryClass ?: 256,
    )
    /** 行号 → (PCM 样本, 采样率)：按真实字节数限额，低内存设备优先走磁盘缓存。 */
    private val mem = DubbingPcmMemoryCache(maxBytes = pcmMemoryBudgetBytes, maxEntries = 48)

    @Volatile private var tts: TtsEngine? = null
    /** True only for an attach pass that must synthesize fresh speech after an import/restart. */
    @Volatile private var forceRegeneratePcmForAttach: Boolean = false
    /**
     * A forced run may share a video cache directory with earlier attempts. Only cache files
     * atomically written by this engine are readable until the forced run finishes.
     */
    private val freshlySynthesizedCacheFiles =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // PCM generation can use a separate System TTS instance when live playback mode is selected.
    // This keeps background synthesizeToFile work independent from audible timeline speech.
    @Volatile private var synthesisTts: TtsEngine? = null
    @Volatile private var backgroundSynthesisRequested = false
    @Volatile private var backgroundSynthesisUnavailable = false
    @Volatile private var backgroundTtsInitializationJob: Job? = null
    private val backgroundTtsInitLock = Any()
    private val ttsInstances = java.util.concurrent.CopyOnWriteArrayList<TtsEngine>()
    private val fallbackRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Return true when the service has scheduled a safe fallback-engine switch for this failed cue. */
    @Volatile var onFallbackRequested: ((index: Int, detail: String) -> Boolean)? = null
    @Volatile var onSynthesisError: ((String) -> Unit)? = null
    val currentTtsName: String get() = tts?.name.orEmpty()
    /** True when the selected system TTS mode speaks directly instead of preparing PCM. */
    val usesImmediateTimelinePlayback: Boolean
        get() = (tts as? TimelineTtsPlayback)?.useDirectTimelinePlayback == true
    /** Smoothed synthesis-time / produced-audio-time ratio; >1 means generation is slower than real time. */
    @Volatile private var synthesisRealtimeFactorValue: Float = Float.NaN
    val synthesisRealtimeFactor: Float get() = synthesisRealtimeFactorValue
    @Volatile private var synthesisRealtimeSampleCountValue: Int = 0
    val synthesisRealtimeSampleCount: Int get() = synthesisRealtimeSampleCountValue
    /** Adaptive estimate of actual System TTS duration, normalized to its approximate 1x rate. */
    @Volatile private var directTtsMsPerCodePoint: Double = 220.0
    /** Scheduler-aware lag: synthesis wall time divided by the interval before the next speech cue. */
    @Volatile private var synthesisTimelineLagFactorValue: Float = Float.NaN
    val synthesisTimelineLagFactor: Float get() = synthesisTimelineLagFactorValue
    @Volatile private var synthesisTimelineLagSampleCountValue: Int = 0
    val synthesisTimelineLagSampleCount: Int get() = synthesisTimelineLagSampleCountValue
    @Volatile private var nextSpeechStartMsByLine: LongArray = LongArray(0)
    /** Diagnostic callback: the first successful PCM result for this session. */
    @Volatile var onFirstSynthesisReady: ((index: Int, sampleCount: Int, sampleRate: Int) -> Unit)? = null
    /** Diagnostic callback after AudioTrack.play() succeeds, not merely when synthesis is ready. */
    @Volatile var onPlaybackStarted: ((index: Int, playbackGeneration: Int) -> Unit)? = null
    private val synthesisErrorReported = AtomicBoolean(false)
    private val firstSynthesisReported = AtomicBoolean(false)
    /** Emit first-output milestones once per attached subtitle session, not once per cue. */
    private val firstPlaybackReported = AtomicBoolean(false)
    private val firstPlaybackCompletedReported = AtomicBoolean(false)
    private val firstQueuedDelayReported = AtomicBoolean(false)
    private val audioOutputPathReported = AtomicBoolean(false)
    private var lines: List<SubLine> = emptyList()
    /** Raw input is kept only for local diagnostics so rejected/empty translations remain explainable. */
    @Volatile private var inputLinesForDiagnostics: List<SubLine> = emptyList()
    @Volatile private var sourceLanguageForDiagnostics: String = "auto"
    private val silentCueReasonsByIndex = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val silentFallbackReported = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var timeline = DubbingTimeline(emptyList())
    @Volatile private var speed = 1.0f
    /** Latest player position; async PCM loading must not start speech at an old timestamp. */
    @Volatile private var latestTimelineCursor = DubbingTimelineCursor()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var activeSyncRate = 1.0f
    @Volatile private var activePlayerSpeed = 1.0f
    @Volatile private var activePlaybackRate = 1.0f
    @Volatile private var activeVolume = 1.0f
    private val playbackGeneration = AtomicInteger(0)
    /**
     * Serializes the final generation check with AudioTrack.play(). PCM loading and track
     * construction remain outside this lock; only the short start operation is guarded.
     */
    private val playbackControlLock = Any()
    /** Live owner guard supplied by PlaybackService; checked on the audio thread immediately before play(). */
    @Volatile private var activePlaybackGuard: (() -> Boolean)? = null
    @Volatile private var released = false
    /** A fatal synthesis OOM disables this engine's queue until the service retires it. */
    @Volatile private var synthesisMemoryFailure = false
    /** AudioTrack 操作专用单线程：write(MODE_STATIC) 数据拷贝耗时，绝不放主线程 */
    private val audioExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tt-audio").apply { priority = Thread.MAX_PRIORITY }
    }
    /** Serializes cache publication with release so a cancelled old run cannot install stale PCM. */
    private val cacheWriteLock = Any()
    /** Keeps PCM cache-generation reads atomic with synthesis-rate configuration updates. */
    private val cacheConfigurationLock = Any()

    private fun isCacheConfigurationCurrent(expectedGeneration: Int): Boolean =
        synchronized(cacheConfigurationLock) {
            DubbingCacheReadGenerationPolicy.canContinue(
                expectedGeneration = expectedGeneration,
                currentGeneration = cacheInitGeneration.get(),
                playbackIsCurrent = !released,
            )
        }

    private fun isWorkerGenerationCurrent(expectedGeneration: Int): Boolean =
        !released && cacheInitGeneration.get() == expectedGeneration

    private fun markCuePreparedForGeneration(
        index: Int,
        hasAudio: Boolean,
        expectedGeneration: Int,
    ): Boolean = synchronized(cacheConfigurationLock) {
        if (!isWorkerGenerationCurrent(expectedGeneration)) {
            false
        } else {
            markCuePrepared(index, hasAudio)
            true
        }
    }

    private fun markCuePendingForGeneration(index: Int, expectedGeneration: Int): Boolean =
        synchronized(cacheConfigurationLock) {
            if (!isWorkerGenerationCurrent(expectedGeneration)) {
                false
            } else {
                markCuePending(index)
                true
            }
        }
    private val cacheWriterId = nextCacheWriterId.incrementAndGet()
    private val ttsCloseSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    // Completes only after every queued AudioTrack operation and the final track cleanup have drained.
    private val audioCloseSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val readiness = DubbingReadiness()
    private val readinessProgress = DubbingReadinessProgress(readiness)
    private val pending = DubbingWorkQueue()
    /** Cue currently inside the single synthesis worker, protected by the pending-queue lock. */
    @Volatile private var activeSynthesisIndex = -1
    /** Cache misses observed while the same cue is synthesizing are reconsidered when it exits. */
    private val retryAfterActiveSynthesis = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var priorityTarget = -1
    @Volatile private var priorityLookAhead = 2
    @Volatile private var synthJob: Job? = null
    @Volatile private var cacheInitJob: Job? = null
    private val synthStartLock = Any()
    private val synthesisRecoveryRetryCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val cacheInitGeneration = AtomicInteger(0)

    /** Pause/seek/release cannot invalidate a request between its final check and AudioTrack.play(). */
    private fun startTrackIfCurrent(
        target: AudioTrack,
        expectedGeneration: Int,
        startedLineIndex: Int? = null,
    ): Boolean = synchronized(playbackControlLock) {
        if (!DubbingChunkTransitionPolicy.canCommit(
                released = released,
                currentGeneration = playbackGeneration.get(),
                expectedGeneration = expectedGeneration,
                currentTrackIsSource = track === target,
            ) || !isActivePlaybackAllowedLocked()
        ) {
            false
        } else {
            target.play()
            if (startedLineIndex != null) playbackStartedLine = startedLineIndex
            true
        }
    }

    private fun isActivePlaybackAllowedLocked(): Boolean =
        runCatching { activePlaybackGuard?.invoke() ?: true }.getOrDefault(false)

    private fun invalidatePlayback(): Pair<Int, String?> = synchronized(playbackControlLock) {
        playbackStartedLine = -1
        lastFedLine = -1
        _currentLine.value = -1
        activePlaybackGuard = null
        val staleUtteranceId = activeTimelineUtteranceId
        activeTimelineUtteranceId = null
        activeCueOverrunsWindow = false
        activeTrackFinalChunkSampleCount = -1
        playbackGeneration.incrementAndGet() to staleUtteranceId
    }

    private val _preparedUntil = MutableStateFlow(0)   // 连续前缀就绪行数，用于定位最早未就绪行
    val preparedUntil: StateFlow<Int> = _preparedUntil
    private val _preparedCount = MutableStateFlow(0)
    /** Includes ready rows after the current gap, so progress remains useful after prioritizing a seek. */
    val preparedCount: StateFlow<Int> = _preparedCount
    private val _totalLines = MutableStateFlow(0)
    val totalLines: StateFlow<Int> = _totalLines

    /** Cache validation or synthesis is still running even after playback has entered READY. */
    val isPreparing: Boolean
        get() = !released && (
            cacheInitJob?.isActive == true ||
                synthJob?.isActive == true ||
                pending.isNotEmpty()
            )
    private val _currentLine = MutableStateFlow(-1)
    val currentLine: StateFlow<Int> = _currentLine
    // Set only after AudioTrack.play() succeeds; PCM readiness alone does not mean voice is audible.
    @Volatile private var playbackStartedLine = -1
    /** 混流架构下最近一次挂上总线的行（同 generation 同行不重复挂载）。 */
    @Volatile private var lastFedLine = -1
    private val prefedCueKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val prefeedCompletions = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    @Volatile private var prefeedGeneration = Long.MIN_VALUE
    /** Exact direct-TTS utterance owned by the current playback generation. */
    @Volatile private var activeTimelineUtteranceId: String? = null
    /** Keep an overlong cue intact until it finishes; the service queues intervening cues. */
    @Volatile private var activeCueOverrunsWindow = false
    /** Sample count of the current static buffer when it is the final buffer of an overrun cue. */
    @Volatile private var activeTrackFinalChunkSampleCount = -1

    // ---- Chunk 层（VideoLingo 式相邻句合并 + WSOLA 时长对齐，实时播放适配）----
    /** 块首行 → 合并后的合成文本（相邻两句一口气念，衔接自然）。 */
    private val chunkTextByFirst = HashMap<Int, String>()
    /** 块次行 → 块首行（合成与 PCM 取用都重定向到首行）。 */
    private val chunkRedirect = HashMap<Int, Int>()
    /** 块首行 → 借用间隙后的窗口截止毫秒（WSOLA 对齐目标）。 */
    private val chunkWindowEndByFirst = HashMap<Int, Long>()

    /** PCM 取用统一入口：块次行重定向到首行（已对齐的整块 PCM）。 */
    private fun resolveChunkIndex(idx: Int): Int = chunkRedirect[idx] ?: idx

    /** True only while this overlong line still owns live TTS/PCM output. */
    fun isSyncHoldActiveFor(index: Int): Boolean {
        if (released || _currentLine.value != index || !activeCueOverrunsWindow) return false
        if ((tts as? TimelineTtsPlayback)?.useDirectTimelinePlayback == true) {
            return activeTimelineUtteranceId != null
        }
        val activeTrack = track ?: return activeCueOverrunsWindow
        val finalSamples = activeTrackFinalChunkSampleCount
        if (finalSamples <= 0) return true
        val played = activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
        val finished = activeTrack.playState == AudioTrack.PLAYSTATE_STOPPED || played >= finalSamples.toLong()
        if (!finished) return true
        synchronized(playbackControlLock) {
            if (_currentLine.value == index && track === activeTrack && activeCueOverrunsWindow) {
                activeCueOverrunsWindow = false
                activeTrackFinalChunkSampleCount = -1
            }
        }
        return false
    }

    fun attach(
        tts: TtsEngine,
        lines: List<SubLine>,
        sourceLanguage: String = "auto",
        forceRegenerateDubbing: Boolean = false,
    ) {
        forceRegeneratePcmForAttach = forceRegenerateDubbing
        freshlySynthesizedCacheFiles.clear()
        // Keep a diagnostic reason only for rows that cannot become speech; this avoids allocating
        // a second per-line object for every valid cue while preserving the original translation.
        val translationDiagnostics = HashMap<Int, DubbingTranslationResolution>()
        // Validate every incoming resource and timeline before publishing state. Even an over-limit
        // import must release the TTS instance it handed to this engine.
        val preparedState = try {
            require(lines.size <= DubbingTimeline.MAX_INPUT_CUE_COUNT) {
                "字幕行数超过配音安全上限，无法初始化配音"
            }
            val normalized = lines.mapIndexed { index, line ->
                val resolution = DubbingTranslationPolicy.resolveText(line.text, line.zh, sourceLanguage)
                if (resolution.text.isNullOrBlank() ||
                    !io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(resolution.text.orEmpty())
                ) {
                    translationDiagnostics[index] = resolution
                }
                line.copy(zh = resolution.text)
            }
            // 译文常比原文长：让每句配音在不碰到下一条字幕的前提下借用后面的静音空隙，
            // 播放窗口、合成限速、超长截断都按这个窗口算；字幕显示/导出仍用原始时间。
            val windowed = DubbingTiming.extendCueWindows(normalized)
            windowed to DubbingTimeline(windowed)
        } catch (error: Exception) {
            logDubbingError(
                "配音引擎校验字幕/时间轴失败",
                error,
                mapOf(
                    "配音引擎名称" to tts.name,
                    "配音引擎实现类" to tts.javaClass.name,
                    "输入字幕行数" to lines.size.toString(),
                    "输入源语言" to sourceLanguage,
                    "允许的最大字幕行数" to DubbingTimeline.MAX_INPUT_CUE_COUNT.toString(),
                ),
            )
            runCatching { tts.close() }
            throw error
        }
        val preparedLines = preparedState.first
        val preparedTimeline = preparedState.second
        val preflightReasons = HashMap<Int, String>()
        val reasonCounts = linkedMapOf<String, Int>()
        val preflightDetails = ArrayList<String>(40)
        var preflightIssueCount = 0
        for (index in preparedLines.indices) {
            val cue = preparedLines[index]
            val rawCue = lines.getOrNull(index)
            val reason = when {
                rawCue == null -> "找不到原始字幕记录"
                rawCue.text.isBlank() -> "原文字幕为空"
                cue.startMs < 0L || cue.endMs <= cue.startMs ->
                    "字幕时间轴无效（开始=${cue.startMs} ms，结束=${cue.endMs} ms）"
                cue.zh.isNullOrBlank() ->
                    translationDiagnostics[index]?.reason ?: "翻译结果为空或未通过译文可用性校验"
                !io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(cue.zh.orEmpty()) ->
                    "译文只有标点、符号或表情，没有可朗读内容"
                else -> null
            } ?: continue
            preflightReasons[index] = reason
            preflightIssueCount++
            reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
            if (preflightDetails.size < 40) {
                preflightDetails += "第 ${index + 1}/${preparedLines.size} 行，" +
                    "${rawCue?.startMs ?: cue.startMs}–${rawCue?.endMs ?: cue.endMs} ms；原因=$reason；" +
                    "原文=${rawCue?.text.orEmpty().take(100)}；" +
                    "输入译文=${rawCue?.zh.orEmpty().take(100)}；" +
                    "实际配音文本=${cue.zh.orEmpty().take(100)}"
            }
        }
        if (preflightIssueCount > 0) {
            logDubbingInfo(
                "配音字幕预检发现 $preflightIssueCount 条字幕无法生成中文配音",
                mapOf(
                    "配音引擎名称" to tts.name,
                    "配音引擎实现类" to tts.javaClass.name,
                    "配音引擎缓存/模型标识" to runCatching { tts.cacheKey }.getOrDefault("读取失败").take(1200),
                    "源语言标记" to sourceLanguage,
                    "字幕总行数" to preparedLines.size.toString(),
                    "无法生成配音字幕数" to preflightIssueCount.toString(),
                    "无法配音原因统计" to reasonCounts.entries.joinToString("；") { "${it.key}：${it.value} 条" },
                    "前 40 条逐句诊断" to preflightDetails.joinToString("\n"),
                    "其余字幕详情" to if (preflightIssueCount > preflightDetails.size) {
                        "还有 ${preflightIssueCount - preflightDetails.size} 条未在此摘要展开；播放到相应字幕时会记录逐句原声回退原因"
                    } else "无",
                ),
                tag = "DubbingFallback",
            )
        }
        var ttsPublished = false
        try {
            // Publish the TTS instance, subtitle list and cache-init Job atomically with release().
            // Otherwise a concurrent retirement could miss this TTS instance or the newly launched job.
            synchronized(cacheWriteLock) {
                if (released) throw IllegalStateException("配音引擎已释放，不能重新绑定字幕")
                this.tts = tts
                this.synthesisTts = tts
                backgroundSynthesisRequested = false
                backgroundSynthesisUnavailable = false
                ttsInstances.clear()
                ttsInstances.add(tts)
                ttsPublished = true
                fallbackRequested.set(false)
                this.lines = preparedLines
                this.inputLinesForDiagnostics = lines
                this.sourceLanguageForDiagnostics = sourceLanguage
                this.silentCueReasonsByIndex.clear()
                this.silentCueReasonsByIndex.putAll(preflightReasons)
                this.silentFallbackReported.clear()
                this.timeline = preparedTimeline
                // VideoLingo 式分块：预算放不下的相邻句合并成块一口气合成（衔接自然），
                // 块 PCM 由 WSOLA 变速不变调对齐到窗口（每条都配完、不暂停视频）。
                chunkTextByFirst.clear()
                chunkRedirect.clear()
                chunkWindowEndByFirst.clear()
                for (chunk in DubbingChunkPolicy.buildChunks(preparedLines)) {
                    chunkTextByFirst[chunk.firstIndex] = chunk.text
                    chunkWindowEndByFirst[chunk.firstIndex] = chunk.windowEndMs
                    for (secondary in chunk.lineIndices.drop(1)) {
                        chunkRedirect[secondary] = chunk.firstIndex
                    }
                }
                val nextSpeechStarts = LongArray(preparedLines.size) { Long.MAX_VALUE }
                var nextSpeechStart = Long.MAX_VALUE
                for (lineIndex in preparedLines.indices.reversed()) {
                    nextSpeechStarts[lineIndex] = nextSpeechStart
                    val line = preparedLines[lineIndex]
                    if (DubbingTimeline.isPlayableCue(line) && !line.zh.isNullOrBlank()) {
                        nextSpeechStart = line.startMs
                    }
                }
                nextSpeechStartMsByLine = nextSpeechStarts
            synthesisErrorReported.set(false)
            firstSynthesisReported.set(false)
            firstPlaybackReported.set(false)
            firstPlaybackCompletedReported.set(false)
            audioOutputPathReported.set(false)
            synthesisRealtimeFactorValue = Float.NaN
            synthesisRealtimeSampleCountValue = 0
            synthesisTimelineLagFactorValue = Float.NaN
            synthesisTimelineLagSampleCountValue = 0
            _totalLines.value = this.lines.size
            readinessProgress.clear(this.lines.size, ::publishReadinessProgress)
            pending.clear()
            priorityTarget = -1
            priorityLookAhead = 2
            latestTimelineCursor = DubbingTimelineCursor()
            logDubbingInfo(
                "配音会话已绑定字幕",
                mapOf(
                    "配音引擎名称" to tts.name,
                    "配音引擎实现类" to tts.javaClass.name,
                    "配音输出模式" to if (tts is TimelineTtsPlayback && tts.useDirectTimelinePlayback) {
                        "Android 系统 TTS 即时朗读"
                    } else {
                        "PCM 合成后挂载混流总线"
                    },
                    "输入源语言" to sourceLanguage,
                    "字幕总行数" to this.lines.size.toString(),
                    "有效时间轴行数" to this.lines.count { DubbingTimeline.isPlayableCue(it) }.toString(),
                    "含中文译文的可配音行数" to this.lines.count {
                        DubbingTimeline.isPlayableCue(it) && !it.zh.isNullOrBlank()
                    }.toString(),
                    "缺少中文译文的可配音行数" to this.lines.count {
                        DubbingTimeline.isPlayableCue(it) && it.zh.isNullOrBlank()
                    }.toString(),
                    "合并配音分块数" to chunkTextByFirst.size.toString(),
                    "PCM 缓存目录可写" to cacheDir.canWrite().toString(),
                    "缓存目录可用空间 MB" to (cacheDir.usableSpace / (1024L * 1024L)).toString(),
                    "PCM 内存缓存预算 bytes" to pcmMemoryBudgetBytes.toString(),
                    "混流总线代次" to outputBus.generationToken().toString(),
                ),
            )
            cacheInitJob?.cancel()
            val generation = synchronized(cacheConfigurationLock) {
                cacheInitGeneration.incrementAndGet()
            }
            // 清内存缓存：防止旧会话同 idx 行的音频串台。
            synchronized(mem) { mem.clear() }

            // Inspecting PCM validates every sample, so doing this loop on the caller thread can
            // freeze playback UI on older phones. Move cache scanning and pruning to Dispatchers.IO.
            cacheInitJob = scope.launch(Dispatchers.IO) {
                val cacheInitCoroutine = kotlinx.coroutines.currentCoroutineContext()[Job]
                fun checkCacheInitActive() {
                    if (released || generation != cacheInitGeneration.get() ||
                        cacheInitCoroutine?.isActive == false
                    ) {
                        throw kotlinx.coroutines.CancellationException("配音缓存校验已取消")
                    }
                }
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (released || generation != cacheInitGeneration.get()) return@launch
                    logDubbingInfo(
                        "配音缓存预检开始",
                        mapOf(
                            "缓存初始化代次" to generation.toString(),
                            "字幕总行数" to this@DubbingEngine.lines.size.toString(),
                            "配音引擎" to (tts?.name ?: "未知引擎"),
                            "配音输出模式" to if (usesImmediateTimelinePlayback) "系统 TTS 即时模式" else "PCM 缓存/混流模式",
                            "强制重新合成配音" to forceRegeneratePcmForAttach.toString(),
                            "缓存文件数" to cacheDir.listFiles().orEmpty().count { it.isFile }.toString(),
                            "缓存目录可用空间 MB" to (cacheDir.usableSpace / (1024L * 1024L)).toString(),
                        ),
                    )

                    // Instant System TTS keeps its direct audible playback path. Queue valid cues
                    // for optional PCM warm-up, but do not allocate a second TTS service or begin
                    // synthesis until the user actually selects original-only playback.
                    if (tts is TimelineTtsPlayback && tts.useDirectTimelinePlayback) {
                        for (i in this@DubbingEngine.lines.indices) {
                            checkCacheInitActive()
                            val cue = this@DubbingEngine.lines[i]
                            val hasSpeech = DubbingTimeline.isPlayableCue(cue) &&
                                !cue.zh.isNullOrBlank()
                            // An explicit re-import must not leave old PCM available as a fallback
                            // if live TTS or optional background synthesis later fails.
                            if (forceRegeneratePcmForAttach) {
                                val file = cacheFile(i)
                                deleteCacheIfUnchanged(file, DubbingCacheFileStamp.capture(file))
                            }
                            // Direct playback is immediately available; queued indexes separately
                            // track which cues still need reusable generated PCM.
                            markCuePrepared(i, hasAudio = hasSpeech)
                            if (hasSpeech) pending.offerLast(i)
                        }
                        // The user can select original-only mode before this IO pass finishes.
                        // Apply that already-published playhead priority to the freshly built queue.
                        synchronized(pending) {
                            val target = priorityTarget
                            if (target in this@DubbingEngine.lines.indices) {
                                val queued = mutableListOf<Int>()
                                pending.drainTo(queued)
                                DubbingQueueOrder.prioritize(
                                    queued,
                                    target,
                                    this@DubbingEngine.lines.size,
                                    lookAhead = priorityLookAhead,
                                ).forEach { pending.offerLast(it) }
                            }
                        }
                        bumpPrepared()
                        logDubbingInfo(
                            "系统 TTS 即时配音初始化完成",
                            mapOf(
                                "字幕总行数" to this@DubbingEngine.lines.size.toString(),
                                "已就绪行数" to _preparedCount.value.toString(),
                                "可配音译文行数" to pending.totalSize().toString(),
                                "即时朗读模式" to "true",
                                "是否请求后台 PCM 预热" to backgroundSynthesisRequested.toString(),
                                "配音引擎" to (tts?.name ?: "未知引擎"),
                            ),
                        )
                        if (backgroundSynthesisRequested) ensureBackgroundSynthesisEngine()
                        return@launch
                    }

                    // Keep valid PCM caches for other voices/engines. They are harmless when
                    // the active session uses a different cache key; the shared LRU budget below
                    // can evict them when needed. Deleting all non-current names here raced with
                    // overlapping old/new engines for the same video and discarded reusable audio.
                    synchronized(cacheInstallLock) {
                        val nowMs = System.currentTimeMillis()
                        val existing = cacheDir.listFiles().orEmpty()
                        for (file in existing) {
                            checkCacheInitActive()
                            if (released || generation != cacheInitGeneration.get()) return@launch
                            if (isStalePcmTemp(file, nowMs)) file.delete()
                        }
                    }

                    for (i in this@DubbingEngine.lines.indices) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (released || generation != cacheInitGeneration.get()) return@launch
                        val cue = this@DubbingEngine.lines[i]
                        if (!DubbingTimeline.isPlayableCue(cue)) {
                            // Invalid timing or blank source text can never be selected by lineAt().
                            // Remove this engine's now-unusable cache and avoid PCM inspection/TTS.
                            val invalidCache = cacheFile(i)
                            deleteCacheIfUnchanged(invalidCache, DubbingCacheFileStamp.capture(invalidCache))
                            markCuePrepared(i, hasAudio = false)
                            continue
                        }
                        val file = cacheFile(i)
                        // Returning to a previously used engine combination must not reuse its old
                        // PCM when processing is explicitly restarted after an engine change.
                        // Queue a fresh synthesis; writeCache atomically replaces this canonical file.
                        val cacheInspection = if (forceRegeneratePcmForAttach) {
                            // Bypass and remove the active identity before queueing fresh synthesis.
                            // Otherwise a failed replacement could still let load() play the old
                            // PCM because playback reads the canonical cache path independently.
                            val oldStamp = DubbingCacheFileStamp.capture(file)
                            deleteCacheIfUnchanged(file, oldStamp)
                            DubbingCacheInspection(null, DubbingCacheFileStamp.capture(file))
                        } else {
                            inspectCache(file, ::checkCacheInitActive)
                        }
                        val cacheHasAudio = cacheInspection.hasAudio
                        val blankTranslation = cue.zh.isNullOrBlank()
                        when {
                            blankTranslation && cacheHasAudio == false ->
                                markCuePrepared(i, hasAudio = false)
                            blankTranslation && cacheHasAudio == true -> {
                                // A blank translation must never reuse a non-empty cache entry.
                                deleteCacheIfUnchanged(file, cacheInspection.stamp)
                                pending.offerLast(i)
                            }
                            !blankTranslation && cacheHasAudio == true ->
                                markCuePrepared(i, hasAudio = true)
                            !blankTranslation && cacheHasAudio == false -> {
                                // Empty PCM from a failed old run isn't a successful cache hit.
                                deleteCacheIfUnchanged(file, cacheInspection.stamp)
                                pending.offerLast(i)
                            }
                            else -> pending.offerLast(i)
                        }
                    }

                    pruneOldDubbingCaches()

                    // A seek can happen while cache validation is running. Honor the latest cue
                    // before starting synthesis instead of letting the whole first pass run in order.
                    synchronized(pending) {
                        val target = priorityTarget
                        if (target in this@DubbingEngine.lines.indices) {
                            val queued = mutableListOf<Int>()
                            pending.drainTo(queued)
                            DubbingQueueOrder.prioritize(
                                queued,
                                target,
                                this@DubbingEngine.lines.size,
                            ).forEach { pending.offerLast(it) }
                        }
                    }
                    if (!released && generation == cacheInitGeneration.get()) {
                        bumpPrepared()
                        logDubbingInfo(
                            "配音 PCM 缓存预检完成",
                            mapOf(
                                "缓存初始化代次" to generation.toString(),
                                "字幕总行数" to this@DubbingEngine.lines.size.toString(),
                                "当前已就绪行数" to _preparedCount.value.toString(),
                                "连续就绪前缀行数" to _preparedUntil.value.toString(),
                                "待合成队列行数" to pending.totalSize().toString(),
                                "有效缓存文件数" to cacheDir.listFiles().orEmpty().count {
                                    it.isFile && it.name.endsWith(".pcm")
                                }.toString(),
                                "配音引擎" to (tts?.name ?: "未知引擎"),
                                "缓存目录可用空间 MB" to (cacheDir.usableSpace / (1024L * 1024L)).toString(),
                            ),
                        )
                        startSynthLoop()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("DubbingEngine", "PCM cache initialization failed", e)
                    runCatching {
                        io.translatetube.local.util.AppLogStore.warning(
                            appContext, "DubbingEngine",
                            "配音 PCM 缓存初始化失败，正在重建待合成队列并继续尝试", e,
                            diagnosticContext = mapOf(
                                "缓存初始化代次" to generation.toString(),
                                "当前缓存初始化代次" to cacheInitGeneration.get().toString(),
                                "字幕总行数" to lines.size.toString(),
                                "配音引擎" to (tts?.name ?: "未知引擎"),
                                "是否已经释放" to released.toString(),
                            ),
                        )
                    }
                    if (!released && generation == cacheInitGeneration.get()) {
                        // Cache I/O failure must not permanently strand the dubbing queue.
                        pending.clear()
                        this@DubbingEngine.lines.indices
                            .filter { DubbingTimeline.isPlayableCue(this@DubbingEngine.lines[it]) }
                            .filterNot { readiness.isReady(it) }
                            .forEach { pending.offerLast(it) }
                        this@DubbingEngine.lines.indices
                            .filterNot { DubbingTimeline.isPlayableCue(this@DubbingEngine.lines[it]) }
                            .forEach { markCuePrepared(it, hasAudio = false) }
                        bumpPrepared()
                        startSynthLoop()
                    }
                }
            }
        }
        } catch (error: Exception) {
            // If release won the race before ownership was published, close the incoming engine
            // here; otherwise release() now owns it and will close it after active synthesis exits.
            if (!ttsPublished) runCatching { tts.close() }
            logDubbingError(
                "配音会话初始化失败",
                error,
                mapOf(
                    "配音引擎名称" to tts.name,
                    "配音引擎实现类" to tts.javaClass.name,
                    "字幕总行数" to preparedLines.size.toString(),
                    "是否已接管 TTS 实例" to ttsPublished.toString(),
                    "是否已经释放" to released.toString(),
                ),
            )
            throw error
        }
    }


    /**
     * Promote the current cue and a configurable look-ahead window. Startup can request a deeper
     * buffer for slow synthesizers; ordinary ticker calls preserve that learned window instead of
     * shrinking it back to two rows.
     */
    fun prioritizeAround(index: Int, lookAhead: Int? = null) {
        if (released || synthesisMemoryFailure || index !in lines.indices) return
        val requestedAhead = (lookAhead ?: priorityLookAhead).coerceIn(2, 12)
        if (index == priorityTarget && requestedAhead <= priorityLookAhead) return
        synchronized(pending) {
            if (index == priorityTarget && requestedAhead <= priorityLookAhead) return
            val queued = mutableListOf<Int>()
            pending.drainTo(queued)
            val effectiveAhead = maxOf(priorityLookAhead, requestedAhead)
            val reordered = DubbingQueueOrder.prioritize(
                queued,
                index,
                lines.size,
                lookAhead = effectiveAhead,
            )
            reordered.forEach { pending.offerLast(it) }
            priorityTarget = index
            priorityLookAhead = effectiveAhead
        }
    }

    /**
     * Keep producing cached Chinese PCM while the user listens to source audio. Generated-mode
     * engines already run this worker continuously; this method additionally enables the optional
     * PCM warm-up path for instant System TTS without playing any Chinese audio.
     */
    fun prepareForBackgroundPlayback(index: Int = -1, lookAhead: Int = 8) {
        if (released || synthesisMemoryFailure) return
        backgroundSynthesisRequested = true
        if (index in lines.indices) prioritizeAround(index, lookAhead)
        if (usesImmediateTimelinePlayback && synthesisTts === tts) {
            ensureBackgroundSynthesisEngine()
        } else if (pending.isNotEmpty()) {
            startSynthLoop()
        }
    }

    private fun ensureBackgroundSynthesisEngine() {
        if (released || !backgroundSynthesisRequested || !usesImmediateTimelinePlayback) return
        if (synthesisTts !== tts) {
            if (pending.isNotEmpty()) startSynthLoop()
            return
        }
        synchronized(backgroundTtsInitLock) {
            if (released || backgroundSynthesisUnavailable || backgroundTtsInitializationJob?.isActive == true) return
            backgroundTtsInitializationJob = scope.launch(Dispatchers.IO) {
                val generated = try {
                    io.translatetube.local.tts.SystemTts(
                        appContext,
                        io.translatetube.local.tts.SystemTtsPlaybackMode.GENERATED,
                    )
                } catch (error: Exception) {
                    backgroundSynthesisUnavailable = true
                    android.util.Log.w(
                        "DubbingEngine",
                        "Unable to initialize background System TTS synthesis; instant playback remains available",
                        error,
                    )
                    return@launch
                }
                val published = synchronized(cacheWriteLock) {
                    if (released) false
                    else {
                        synthesisTts = generated
                        ttsInstances.addIfAbsent(generated)
                        true
                    }
                }
                if (!published) {
                    runCatching { generated.close() }
                    return@launch
                }
                if (backgroundSynthesisRequested && pending.isNotEmpty()) startSynthLoop()
            }
        }
    }

    private fun startSynthLoop() {
        // Instant System TTS uses its own background generator and only starts that generator after
        // source-only mode asks for it. Generated-mode engines keep their existing eager pipeline.
        if (usesImmediateTimelinePlayback &&
            (!backgroundSynthesisRequested || backgroundSynthesisUnavailable || synthesisTts == null || synthesisTts === tts)
        ) return
        // Do not continually launch empty workers from the high-frequency player ticker.
        if (pending.isEmpty()) return
        // Queue recovery and cache initialization can request startup concurrently. Serialize
        // the check-and-launch pair; otherwise both callers could observe an idle worker and
        // invoke the same native TTS engine concurrently.
        synchronized(synthStartLock) {
            // Never cancel an active provider call just to restart queue processing: many TTS
            // providers perform blocking native work and cancellation cannot stop it immediately.
            if (released || synthesisMemoryFailure || synthJob?.isActive == true) return
            val workerCacheGeneration = synchronized(cacheConfigurationLock) {
                cacheInitGeneration.get()
            }
            synthJob = scope.launch(Dispatchers.Default) {
            val workerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            fun isWorkerTaskCurrent(): Boolean =
                DubbingSynthesisGenerationPolicy.canContinue(
                    expectedGeneration = workerCacheGeneration,
                    currentGeneration = cacheInitGeneration.get(),
                    taskActive = workerJob?.isActive == true,
                    engineReleased = released,
                )
            try {
            // Poll under the same lock used by prioritizeAround(). Do not inspect isEmpty()
            // outside the lock: queue reordering temporarily drains the deque, and a separate
            // isEmpty() check could let this worker exit before the reordered work is restored.
            while (!released) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!isWorkerTaskCurrent()) {
                    throw kotlinx.coroutines.CancellationException("配音合成配置已变化")
                }
                // A fallback request stops this worker at a line boundary. The service waits for
                // it to unwind before swapping engines, preventing old TTS PCM from being written
                // into the new engine's cache namespace.
                if (fallbackRequested.get()) break
                val idx = synchronized(pending) {
                    val next = pending.pollFirst()
                    if (next != null) activeSynthesisIndex = next
                    next
                } ?: break
                // Keep the worker identity visible to finally: cancelled/reconfigured workers
                // must clear their active slot but must not repopulate the replacement queue.
                var deterministicFailureInThisCue = false
                try {
                val line = lines.getOrNull(idx) ?: continue
                if (!DubbingTimeline.isPlayableCue(line)) {
                    markCuePreparedForGeneration(idx, hasAudio = false, expectedGeneration = workerCacheGeneration)
                    bumpPrepared()
                    continue
                }
                fun checkSynthesisActive() {
                    if (!isWorkerTaskCurrent()) {
                        throw kotlinx.coroutines.CancellationException("配音缓存写入已取消或配置代次已过期")
                    }
                }
                val cachedAudioState = cached(idx, ::checkSynthesisActive)
                checkSynthesisActive()
                if (cachedAudioState != null) {
                    synthesisRecoveryRetryCounts.remove(idx)
                    // A cache file is usable only after its header and exact payload length are
                    // validated. Preserve empty failure markers separately from corrupt entries.
                    markCuePreparedForGeneration(idx, hasAudio = cachedAudioState, expectedGeneration = workerCacheGeneration)
                    if (!released && cachedAudioState && firstSynthesisReported.compareAndSet(false, true)) {
                        runCatching {
                            val audioFromMemory = memoryAudio(idx)
                            if (audioFromMemory != null) {
                                logDubbingInfo(
                                    "首条可用配音 PCM 来自内存缓存",
                                    mapOf(
                                        "字幕行号" to "${idx + 1}/${lines.size}",
                                        "PCM 样本数" to audioFromMemory.samples.size.toString(),
                                        "采样率 Hz" to audioFromMemory.sampleRate.toString(),
                                        "预计音频时长 ms" to (audioFromMemory.samples.size.toDouble() * 1000.0 /
                                            audioFromMemory.sampleRate.coerceAtLeast(1)).toLong().toString(),
                                        "配音引擎" to (tts?.name ?: "未知引擎"),
                                    ),
                                )
                                onFirstSynthesisReady?.invoke(
                                    idx,
                                    audioFromMemory.samples.size,
                                    audioFromMemory.sampleRate,
                                )
                            } else {
                                java.io.DataInputStream(
                                    java.io.BufferedInputStream(java.io.FileInputStream(cacheFile(idx)))
                                ).use { input ->
                                    val sampleRate = input.readInt()
                                    val sampleCount = input.readInt()
                                    if (sampleRate !in 8_000..192_000 || sampleCount <= 0) {
                                        throw java.io.IOException("缓存 PCM 头无效")
                                    }
                                    if (!released) {
                                        logDubbingInfo(
                                            "首条可用配音 PCM 命中磁盘缓存",
                                            mapOf(
                                                "字幕行号" to "${idx + 1}/${lines.size}",
                                                "PCM 样本数" to sampleCount.toString(),
                                                "采样率 Hz" to sampleRate.toString(),
                                                "预计音频时长 ms" to (sampleCount.toDouble() * 1000.0 /
                                                    sampleRate.coerceAtLeast(1)).toLong().toString(),
                                                "缓存文件大小 bytes" to cacheFile(idx).length().toString(),
                                                "配音引擎" to (tts?.name ?: "未知引擎"),
                                            ),
                                        )
                                        onFirstSynthesisReady?.invoke(idx, sampleCount, sampleRate)
                                    }
                                }
                            }
                        }.onFailure {
                            android.util.Log.w("DubbingEngine", "Could not publish cached-PCM diagnostic", it)
                        }
                    }
                    bumpPrepared()
                    continue
                }
                // 块次行：随块首行一起合成（首行完成时由 markCuePrepared 同步补标记），
                // 不在合成队列里单独处理；首行尚未合成/失败时先保持未就绪。
                val redirectFirstIndex = chunkRedirect[idx]
                if (redirectFirstIndex != null) {
                    when {
                        readiness.isPrepared(redirectFirstIndex) -> markCuePreparedForGeneration(idx, hasAudio = true, expectedGeneration = workerCacheGeneration)
                        readiness.isSilent(redirectFirstIndex) -> markCuePreparedForGeneration(idx, hasAudio = false, expectedGeneration = workerCacheGeneration)
                        else -> Unit // 等首行完成时统一补标
                    }
                    bumpPrepared()
                    continue
                }
                // 只配中文译文；未翻译的行写空标记（原声顶上，且进度能连续推进）
                val text = line.zh
                // 只有标点/符号/表情的译文念不出声，任何引擎都会拿不到音频：当作静音句，不报合成失败。
                if (text.isNullOrBlank() || !io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(text)) {
                    val markerSpeed = speed
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    // An empty translation is intentionally silent. Persisting its empty marker is
                    // only an optimization; a full cache must not terminate the whole TTS queue.
                    try {
                        writeCache(
                            idx,
                            16000,
                            FloatArray(0),
                            cacheSpeed = markerSpeed,
                            checkActive = ::checkSynthesisActive,
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "DubbingEngine",
                            "Could not persist empty-translation marker for line ${idx + 1}; keeping session readiness",
                            e,
                        )
                    }
                    markCuePreparedForGeneration(idx, hasAudio = false, expectedGeneration = workerCacheGeneration)
                    bumpPrepared()
                    continue
                }
                var synthesisCallCount = 0
                try {
                    // One cue must use one synthesis-rate snapshot for its primary attempt,
                    // adaptive retry and cache identity even if Settings changes mid-request.
                    val synthesisSpeed = speed
                    val synthesisStartedAtNanos = System.nanoTime()
                    var audio: io.translatetube.local.tts.TtsAudio? = null
                    var lastFailure: Exception? = null
                    // 块首行用合并文本一口气合成（相邻句衔接自然）；普通行保持原文。
                    val synthText = chunkTextByFirst[idx] ?: text
                    for (attempt in 0..1) {
                        try {
                            synthesisCallCount++
                            val generated = (synthesisTts ?: tts)!!.synthCancellable(synthText, synthesisSpeed, line.speakerId, ::checkSynthesisActive)
                            // TTS providers perform blocking work; observe cancellation as soon
                            // as the provider returns, before installing audio into the cache.
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (released) throw kotlinx.coroutines.CancellationException("配音引擎已释放")
                            if (generated.samples.size > DubbingPcmValidation.MAX_SAMPLE_COUNT) {
                                throw DubbingPcmLimitException(
                                    "单句配音样本数超过 ${DubbingPcmValidation.MAX_SAMPLE_COUNT} 上限；为保护设备内存，已跳过重复合成"
                                )
                            }
                            if (!DubbingPcmValidation.isValidAudio(
                                    generated.sampleRate,
                                    generated.samples,
                                    checkActive = {
                                        checkSynthesisActive()
                                    },
                                )
                            ) {
                                throw IllegalStateException(
                                    "所选引擎返回静音、无效采样或不支持的采样率（${generated.sampleRate} Hz）"
                                )
                            }
                            // 去掉首尾静音（对所有引擎统一生效）：在线引擎（微软 Edge 等）返回的音频
                            // 开头常带 100～300ms 静音，不去掉的话配音永远比视频原声晚开口。
                            // 时长拟合在下面（含必要时的原生提速重合成）之后对最终 PCM 统一做一次，
                            // 避免对一版即将被替换的音频白做变速。
                            audio = trimEdgeSilence(generated, ::checkSynthesisActive)
                            break
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastFailure = e
                            // The same text/provider cannot benefit from another synthesis pass
                            // when its result already exceeds the hard per-cue PCM cap.
                            if (e is DubbingPcmLimitException || e is TtsInputLimitException) break
                            if (attempt == 0 && !released) {
                                android.util.Log.w(
                                    "DubbingEngine",
                                    "TTS line ${idx + 1} failed once; retrying with the same selected engine",
                                    e
                                )
                                kotlinx.coroutines.delay(300L)
                            }
                        }
                    }
                    val firstAudio = audio ?: throw (lastFailure
                        ?: IllegalStateException("配音引擎没有返回有效音频"))
                    // 本块可用的时间窗口：块首行开始 → 块末（含借用的静音间隙）。合并块覆盖两句，
                    // 必须按整块窗口而不是单行时长判断，否则会误以为"太长"而反复重合成。
                    val windowMs = ((chunkWindowEndByFirst[idx] ?: line.endMs) - line.startMs).coerceAtLeast(1L)
                    // WSOLA 单独最多压缩 MAX_COMPRESS 倍；原始音频超出这个预算时，才值得让引擎
                    // 原生提速重新合成（原生提速的韵律比大比例变速自然）。
                    val cueDurationMs = DubbingFitPolicy.nativeRetryBudgetMs(windowMs)
                    // TTS engines may not scale linearly with their requested speech rate. Measure
                    // each accepted render and allow up to two adaptive passes before playback,
                    // stopping as soon as the measured PCM fits the safe timeline multiplier.
                    var readyAudio = firstAudio
                    var effectiveSynthesisSpeed = synthesisSpeed
                    var adaptiveRetryCount = 0
                    while (adaptiveRetryCount < DubbingSynthesisPolicy.MAX_ADAPTIVE_SYNTHESIS_RETRIES) {
                        val retrySpeed = DubbingSynthesisPolicy.recommendedSpeed(
                            baseSpeed = effectiveSynthesisSpeed,
                            sampleCount = readyAudio.samples.size,
                            sampleRate = readyAudio.sampleRate,
                            cueDurationMs = cueDurationMs,
                        )
                        if (retrySpeed <= effectiveSynthesisSpeed + 0.05f) break

                        try {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            synthesisCallCount++
                            // 必须用与首次合成相同的文本：合并块是"两句一口气念"，只重合成
                            // 第一句会把第二句整句丢掉。
                            val rawCandidate = (synthesisTts ?: tts)!!.synthCancellable(
                                synthText, retrySpeed, line.speakerId, ::checkSynthesisActive,
                            )
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val candidateWithinSampleLimit =
                                rawCandidate.samples.size <= DubbingPcmValidation.MAX_SAMPLE_COUNT
                            val candidateIsValid = candidateWithinSampleLimit && DubbingPcmValidation.isValidAudio(
                                rawCandidate.sampleRate,
                                rawCandidate.samples,
                                checkActive = {
                                    checkSynthesisActive()
                                },
                            )
                            // 与首次合成一致：按去掉首尾静音后的真实说话时长比较和缓存。
                            val candidate = if (candidateIsValid) {
                                trimEdgeSilence(rawCandidate, ::checkSynthesisActive)
                            } else {
                                rawCandidate
                            }
                            val currentDurationMs = readyAudio.samples.size.toDouble() * 1_000.0 /
                                readyAudio.sampleRate.coerceAtLeast(1)
                            val candidateDurationMs = candidate.samples.size.toDouble() * 1_000.0 /
                                candidate.sampleRate.coerceAtLeast(1)
                            val candidateIsShorter = candidateIsValid &&
                                candidateDurationMs < currentDurationMs * 0.98
                            if (!candidateIsShorter) {
                                runCatching {
                                    io.translatetube.local.util.AppLogStore.warning(
                                        appContext,
                                        "DubbingEngine",
                                        "第 ${idx + 1}/${lines.size} 句自适应语速合成未产生更短的有效音频，保留上一版结果",
                                        diagnosticContext = mapOf(
                                            "字幕行号" to "${idx + 1}/${lines.size}",
                                            "配音引擎" to (tts?.name ?: "未知引擎"),
                                            "当前合成语速" to effectiveSynthesisSpeed.toString(),
                                            "尝试语速" to retrySpeed.toString(),
                                            "当前音频时长 ms" to currentDurationMs.toLong().toString(),
                                            "候选音频时长 ms" to candidateDurationMs.toLong().toString(),
                                            "候选音频有效" to candidateIsValid.toString(),
                                            "候选音频在样本上限内" to candidateWithinSampleLimit.toString(),
                                            "处理方式" to "不替换已验证的 PCM，后续由时间轴兜底处理",
                                        ),
                                    )
                                }
                                break
                            }

                            readyAudio = candidate
                            effectiveSynthesisSpeed = retrySpeed
                            adaptiveRetryCount++
                            android.util.Log.i(
                                "DubbingEngine",
                                "Adaptive synthesis accepted: line=${idx + 1}/${lines.size}, " +
                                    "pass=$adaptiveRetryCount, rate=$effectiveSynthesisSpeed, " +
                                    "durationMs=${candidateDurationMs.toLong()}",
                            )
                            // Keep adapting until the measured render leaves headroom in the real cue
                            // window. The emergency playback-rate cap is not a synthesis success target:
                            // accepting a 1.7 s render for a 1 s cue would cause avoidable catch-up drift.
                            if (DubbingSynthesisPolicy.isWithinTimelineTarget(candidateDurationMs, cueDurationMs)) {
                                break
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "DubbingEngine",
                                "Adaptive speech-rate retry failed for line ${idx + 1}; keeping last valid synthesis",
                                e,
                            )
                            runCatching {
                                io.translatetube.local.util.AppLogStore.warning(
                                    appContext,
                                    "DubbingEngine",
                                    "第 ${idx + 1}/${lines.size} 句自适应语速重试失败，已保留此前有效音频",
                                    e,
                                    diagnosticContext = mapOf(
                                        "字幕行号" to "${idx + 1}/${lines.size}",
                                        "配音引擎" to (tts?.name ?: "未知引擎"),
                                        "当前合成语速" to effectiveSynthesisSpeed.toString(),
                                        "尝试语速" to retrySpeed.toString(),
                                        "已接受的自适应次数" to adaptiveRetryCount.toString(),
                                        "处理策略" to "保留此前有效音频，由时间轴兜底处理",
                                    ),
                                )
                            }
                            break
                        }
                    }
                    val synthesisElapsedMs = (System.nanoTime() - synthesisStartedAtNanos)
                        .coerceAtLeast(0L) / 1_000_000L
                    val outputAudioDurationMs = readyAudio.samples.size.toDouble() * 1_000.0 /
                        readyAudio.sampleRate.coerceAtLeast(1)
                    if (outputAudioDurationMs.isFinite() && outputAudioDurationMs > 0.0) {
                        val measuredRtf = (synthesisElapsedMs.toDouble() / outputAudioDurationMs)
                            .toFloat()
                        if (measuredRtf.isFinite() && measuredRtf > 0f) {
                            val previousRtf = synthesisRealtimeFactorValue
                            synthesisRealtimeFactorValue = if (previousRtf.isFinite()) {
                                previousRtf * 0.65f + measuredRtf * 0.35f
                            } else measuredRtf
                            synthesisRealtimeSampleCountValue += 1
                        }
                    }
                    // Audio-duration RTF alone can overestimate lag for sparse subtitles. Compare
                    // generation cost with the actual timeline interval before the next speech cue.
                    val currentTimelineLine = lines.getOrNull(idx)
                    val nextSpeechStart = nextSpeechStartMsByLine.getOrNull(idx) ?: Long.MAX_VALUE
                    if (currentTimelineLine != null && nextSpeechStart != Long.MAX_VALUE) {
                        val timelineBudgetMs =
                            (nextSpeechStart - currentTimelineLine.startMs).coerceAtLeast(500L)
                        val measuredTimelineLag = synthesisElapsedMs.toDouble() / timelineBudgetMs
                        if (measuredTimelineLag.isFinite() && measuredTimelineLag >= 0.0) {
                            val previousLag = synthesisTimelineLagFactorValue
                            synthesisTimelineLagFactorValue = if (previousLag.isFinite()) {
                                previousLag * 0.65f + measuredTimelineLag.toFloat() * 0.35f
                            } else measuredTimelineLag.toFloat()
                            synthesisTimelineLagSampleCountValue += 1
                        }
                    }
                    if (synthesisElapsedMs >= 500L || synthesisCallCount > 1) {
                        android.util.Log.d(
                            "DubbingEngine",
                            "TTS performance: engine=${tts?.name} line=${idx + 1}/${lines.size} " +
                                "chars=${text.length} synthMs=${synthesisElapsedMs} " +
                                "audioMs=${outputAudioDurationMs.toLong()} rtf=${synthesisRealtimeFactorValue} " +
                                "timelineLag=${synthesisTimelineLagFactorValue} samples=${synthesisTimelineLagSampleCountValue} " +
                                "calls=${synthesisCallCount}",
                        )
                    }
                    // 时长拟合：整块 PCM 比窗口长才压缩（变速不变调），够放的保持自然语速，绝不拉长。
                    // 缓存里存的就是拟合后的最终音频；挂载时的排队/追赶由总线处理。
                    readyAudio = fitAudioToWindow(idx, readyAudio, windowMs)
                    val maxUsefulSamples = DubbingSynthesisPolicy.maxUsefulSampleCount(
                        sampleRate = readyAudio.sampleRate,
                        cueDurationMs = cueDurationMs,
                    )
                    val boundedSamples = if (maxUsefulSamples in 1 until readyAudio.samples.size) {
                        DubbingPcmValidation.copyPrefix(
                            samples = readyAudio.samples,
                            requestedCount = maxUsefulSamples,
                            checkActive = {
                                if (released || workerJob?.isActive == false) {
                                    throw kotlinx.coroutines.CancellationException("配音合成任务已取消")
                                }
                            },
                        ).takeIf {
                            DubbingPcmValidation.isValidAudio(
                                readyAudio.sampleRate,
                                it,
                                checkActive = {
                                    if (released || workerJob?.isActive == false) {
                                        throw kotlinx.coroutines.CancellationException("配音合成任务已取消")
                                    }
                                },
                            )
                        } ?: readyAudio.samples
                    } else {
                        readyAudio.samples
                    }
                    var cachePersisted = false
                    var persistedCacheIdentity: String? = null
                    try {
                        persistedCacheIdentity = writeCache(
                            idx,
                            readyAudio.sampleRate,
                            boundedSamples,
                            cacheSpeed = synthesisSpeed,
                            checkActive = ::checkSynthesisActive,
                        )
                        cachePersisted = true
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (cacheError: Exception) {
                        if (released) throw kotlinx.coroutines.CancellationException("配音引擎已释放")
                        // Disk/cache failures are not synthesis failures. Preserve valid generated
                        // audio in the bounded LRU so this cue can still play on low-storage devices.
                        android.util.Log.w(
                            "DubbingEngine",
                            "PCM cache write failed for line ${idx + 1}; trying bounded memory playback",
                            cacheError,
                        )
                        runCatching {
                            io.translatetube.local.util.AppLogStore.warning(
                                appContext, "DubbingEngine",
                                "第 ${idx + 1}/${lines.size} 句配音缓存写入失败，正在尝试内存缓存播放",
                                cacheError,
                                diagnosticContext = mapOf(
                                    "字幕行号" to "${idx + 1}/${lines.size}",
                                    "配音引擎" to (tts?.name ?: "未知引擎"),
                                    "PCM 采样率 Hz" to readyAudio.sampleRate.toString(),
                                    "PCM 采样点数" to boundedSamples.size.toString(),
                                    "音频时长估算 ms" to (boundedSamples.size.toDouble() * 1000.0 / readyAudio.sampleRate.coerceAtLeast(1)).toLong().toString(),
                                    "应用文件目录可用空间 MB" to (appContext.filesDir.usableSpace / (1024L * 1024L)).toString(),
                                    "当前内存缓存条目数" to synchronized(mem) { mem.size }.toString(),
                                    "恢复策略" to "优先保留已合成音频；内存也无法容纳时才报告配音失败",
                                ),
                            )
                        }
                    }
                    checkSynthesisActive()
                    val retainedInMemory = synchronized(mem) {
                        if (!released && workerJob?.isActive != false) {
                            mem[idx] = DubbingPcmMemoryEntry(
                                samples = boundedSamples,
                                sampleRate = readyAudio.sampleRate,
                                cacheIdentity = persistedCacheIdentity ?: cacheFile(idx, synthesisSpeed).name,
                            )
                            mem.containsKey(idx)
                        } else {
                            false
                        }
                    }
                    checkSynthesisActive()
                    if (!cachePersisted && !retainedInMemory) {
                        val cacheFailure = IllegalStateException(
                            "第 ${idx + 1} 句已合成，但缓存写入失败，且该句音频超出可用内存缓存容量，无法安全播放。请释放存储空间后重试。"
                        )
                        silentCueReasonsByIndex[idx] =
                            "音频已合成，但磁盘缓存写入失败且内存缓存无法容纳该句：${cacheFailure.message.orEmpty()}"
                        recordDubbingFailure(idx, "音频缓存", cacheFailure)
                        reportSynthesisError(cacheFailure.message ?: "配音音频缓存失败")
                        markCuePreparedForGeneration(idx, hasAudio = false, expectedGeneration = workerCacheGeneration)
                        bumpPrepared()
                        continue
                    }
                    synthesisRecoveryRetryCounts.remove(idx)
                    silentCueReasonsByIndex.remove(idx)
                    markCuePreparedForGeneration(idx, hasAudio = true, expectedGeneration = workerCacheGeneration)
                    if (!released && firstSynthesisReported.compareAndSet(false, true)) {
                        logDubbingInfo(
                            "首条中文配音 PCM 合成成功",
                            mapOf(
                                "字幕行号" to "${idx + 1}/${lines.size}",
                                "配音引擎名称" to (tts?.name ?: "未知引擎"),
                                "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                                "合成调用次数（含重试）" to synthesisCallCount.toString(),
                                "PCM 样本数" to boundedSamples.size.toString(),
                                "PCM 采样率 Hz" to readyAudio.sampleRate.toString(),
                                "音频时长 ms" to outputAudioDurationMs.toLong().toString(),
                                "本句合成语速" to effectiveSynthesisSpeed.toString(),
                                "本句合成处理耗时 ms" to synthesisElapsedMs.toString(),
                                "合成实时系数" to synthesisRealtimeFactorValue.takeIf { it.isFinite() }?.toString().orEmpty().ifBlank { "尚未形成有效样本" },
                                "是否成功写入磁盘缓存" to cachePersisted.toString(),
                                "是否保留在内存缓存" to retainedInMemory.toString(),
                                "是否为合并配音分块" to chunkTextByFirst.containsKey(idx).toString(),
                                "视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                                "混流总线代次" to outputBus.generationToken().toString(),
                            ),
                        )
                        runCatching {
                            onFirstSynthesisReady?.invoke(idx, boundedSamples.size, readyAudio.sampleRate)
                        }.onFailure {
                            android.util.Log.w("DubbingEngine", "Could not publish first-PCM diagnostic", it)
                        }
                    }
                    bumpPrepared()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Blocking providers may throw ordinary exceptions after cancellation. Reject
                    // that stale failure before it can mark a new configuration silent or enqueue work.
                    if (!isWorkerTaskCurrent()) {
                        throw kotlinx.coroutines.CancellationException("配音任务配置已变化，忽略旧引擎结果")
                    }
                    // After one retry, publish the failure and allow video playback to continue.
                    val detail = e.message ?: e.javaClass.simpleName
                    silentCueReasonsByIndex[idx] =
                        "配音合成/缓存处理失败：${e.javaClass.name}: ${detail.take(320)}"
                    android.util.Log.e("DubbingEngine", "synth fail line $idx using selected engine ${tts?.name}", e)
                    // A playback cache miss may have already requested a retry while this cue
                    // was being synthesized. Defer terminal error reporting until that queued
                    // attempt has run, otherwise a successful immediate retry leaves a false warning.
                    val deterministicFailure = e is DubbingPcmLimitException || e is TtsInputLimitException
                    deterministicFailureInThisCue = deterministicFailure
                    val retryRequestedWhileActive = synchronized(pending) {
                        retryAfterActiveSynthesis.contains(idx)
                    }
                    logDubbingInfo(
                        "配音句合成/缓存处理发生异常，正在判断恢复策略",
                        mapOf(
                            "字幕行号" to "${idx + 1}/${lines.size}",
                            "配音引擎名称" to (tts?.name ?: "未知引擎"),
                            "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                            "故障异常类型" to e.javaClass.name,
                            "故障原因" to detail.take(360),
                            "中文译文字符数" to text.length.toString(),
                            "合成调用次数（含重试）" to synthesisCallCount.toString(),
                            "当前恢复重试次数" to (synthesisRecoveryRetryCounts[idx] ?: 0).toString(),
                            "是否已有同句播放重试等待" to retryRequestedWhileActive.toString(),
                            "备用引擎切换是否已请求" to fallbackRequested.get().toString(),
                            "待合成队列行数" to pending.totalSize().toString(),
                            "缓存目录可用空间 MB" to (cacheDir.usableSpace / (1024L * 1024L)).toString(),
                            "视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                        ),
                    )
                    if (DubbingRetryQueuePolicy.shouldKeepCuePendingAfterFailure(
                            retryRequestedWhileActive = retryRequestedWhileActive,
                            deterministicFailure = deterministicFailure,
                        )
                    ) {
                        // The queued retry is real work, not terminal silence. Keeping readiness
                        // pending prevents tick() from showing a false silent-cue/error state.
                        markCuePendingForGeneration(idx, expectedGeneration = workerCacheGeneration)
                        bumpPrepared()
                        continue
                    }
                    if (requestTtsFallback(idx, detail)) {
                        // The asynchronous service callback will install the fallback and requeue
                        // this cue. Stop before synthesizing another line with the failed engine.
                        markCuePreparedForGeneration(idx, hasAudio = false, expectedGeneration = workerCacheGeneration)
                        bumpPrepared()
                        continue
                    }
                    val retriesAlreadyScheduled = synthesisRecoveryRetryCounts[idx] ?: 0
                    if (DubbingSynthesisFailureRecoveryPolicy.shouldScheduleRetry(
                            retriesAlreadyScheduled = retriesAlreadyScheduled,
                            deterministicFailure = deterministicFailure,
                            released = released,
                        )
                    ) {
                        synthesisRecoveryRetryCounts[idx] = retriesAlreadyScheduled + 1
                        markCuePendingForGeneration(idx, expectedGeneration = workerCacheGeneration)
                        bumpPrepared()
                        android.util.Log.w(
                            "DubbingEngine",
                            "Scheduling one delayed recovery retry: line=${idx + 1}/${lines.size}, " +
                                "engine=${tts?.name}, chars=${text.length}, waitMs=${DubbingSynthesisFailureRecoveryPolicy.RETRY_DELAY_MS}",
                            e,
                        )
                        scope.launch {
                            kotlinx.coroutines.delay(DubbingSynthesisFailureRecoveryPolicy.RETRY_DELAY_MS)
                            if (!isWorkerGenerationCurrent(workerCacheGeneration)) return@launch
                            // Serialize the generation check with insertion. Otherwise a config
                            // change could clear the queue after this check but before offerLast().
                            val enqueued = synchronized(cacheConfigurationLock) {
                                if (!isWorkerGenerationCurrent(workerCacheGeneration)) {
                                    false
                                } else synchronized(pending) {
                                    if (pending.contains(idx) || readiness.isReady(idx)) {
                                        false
                                    } else {
                                        pending.offerLast(idx)
                                    }
                                }
                            }
                            if (enqueued) startSynthLoop()
                        }
                        continue
                    }
                    recordDubbingFailure(idx, "合成", e)
                    reportSynthesisError("第 ${idx + 1} 句，${tts?.name ?: "所选引擎"}合成失败：$detail")
                    // Do not persist a zero-sample marker for a failed synthesis. A transient
                    // service/voice failure should be retried in a future session, not cached as silence.
                    markCuePreparedForGeneration(idx, hasAudio = false, expectedGeneration = workerCacheGeneration)
                    bumpPrepared()
                }
                } finally {
                    // If playAt() observed this cue before its PCM publication, defer the retry
                    // until the active synthesis finishes. Queue only one retry when it failed to
                    // produce a cache, instead of synthesizing the same cue concurrently.
                    val translatedCue = lines.getOrNull(idx)?.let {
                        DubbingTimeline.isPlayableCue(it) && !it.zh.isNullOrBlank()
                    } == true
                    // File existence alone is not proof of reusable audio: a corrupt/replaced file
                    // can survive a stamp-guarded delete. Validate only when playback actually asked
                    // for an active-cue retry, and suppress requeue if this worker was cancelled.
                    val retryInitiallyRequested = synchronized(pending) {
                        retryAfterActiveSynthesis.contains(idx)
                    }
                    var hasReusableAudio = false
                    if (retryInitiallyRequested && translatedCue && isWorkerTaskCurrent()) {
                        try {
                            hasReusableAudio = cached(idx) {
                                if (!isWorkerTaskCurrent()) {
                                    throw kotlinx.coroutines.CancellationException("配音重试状态检查已取消或配置代次已过期")
                                }
                            } == true
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // Cancellation/reconfiguration owns the next queue. Do not enqueue from
                            // this stale worker, but still clear activeSynthesisIndex below.
                        } catch (cacheError: Exception) {
                            android.util.Log.w("DubbingEngine", "Could not validate retry PCM cache", cacheError)
                        }
                    }
                    // Keep the final active-cue retry decision atomic with a config change.
                    // The replacement pass clears/rebuilds pending immediately after advancing
                    // the generation, so a retired worker must not append behind that clear.
                    synchronized(cacheConfigurationLock) {
                        synchronized(pending) {
                            if (activeSynthesisIndex == idx) activeSynthesisIndex = -1
                            val retryWasRequested = retryAfterActiveSynthesis.remove(idx)
                            val workerStillOwnsGeneration =
                                DubbingSynthesisGenerationPolicy.canContinue(
                                    expectedGeneration = workerCacheGeneration,
                                    currentGeneration = cacheInitGeneration.get(),
                                    taskActive = workerJob?.isActive == true,
                                    engineReleased = released,
                                )
                            if (
                                DubbingRetryQueuePolicy.shouldRequeueAfterActiveSynthesis(
                                    retryRequested = retryWasRequested,
                                    translatedCue = translatedCue,
                                    hasReusableAudio = hasReusableAudio,
                                    workerActive = workerStillOwnsGeneration,
                                    released = released,
                                    fallbackRequested = fallbackRequested.get(),
                                    alreadyQueued = pending.contains(idx),
                                    deterministicFailure = deterministicFailureInThisCue,
                                )
                            ) {
                                pending.offerFirst(idx)
                            }
                        }
                    }
                }
            }
            } catch (memoryError: OutOfMemoryError) {
                // An unhandled OOM in this service-scope worker would terminate the whole app.
                // Stop this engine's queue, release cached PCM references and tell the service to
                // retire the native TTS runtime; the source video can continue on its original audio.
                if (!released) {
                    val memoryCacheEntryCountBeforeOom = synchronized(mem) { mem.size }
                    val pendingCountBeforeOom = pending.totalSize()
                    synthesisMemoryFailure = true
                    synchronized(mem) { mem.clear() }
                    synchronized(pending) {
                        pending.clear()
                        retryAfterActiveSynthesis.clear()
                    }
                    android.util.Log.e(
                        "DubbingEngine",
                        "Stopping selected TTS engine after out-of-memory during synthesis",
                        memoryError,
                    )
                    runCatching {
                        io.translatetube.local.util.AppLogStore.error(
                            appContext, "DubbingEngine",
                            "中文配音合成时内存耗尽，已停止当前合成队列并准备恢复视频原声",
                            memoryError,
                            diagnosticContext = mapOf(
                                "配音引擎" to (tts?.name ?: "未知引擎"),
                                "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                                "字幕总行数" to lines.size.toString(),
                                "当前合成字幕索引" to activeSynthesisIndex.toString(),
                                "待合成队列长度" to pendingCountBeforeOom.toString(),
                                "内存缓存清理前条目数" to memoryCacheEntryCountBeforeOom.toString(),
                                "失败处理" to "清空待合成队列和 PCM 内存缓存，向播放服务上报恢复原声",
                            ),
                        )
                    }
                    // OOM must replace any earlier one-time synthesis warning so the service
                    // receives the memory-specific signal and can dispose this native runtime.
                    synthesisErrorReported.set(false)
                    runCatching {
                        reportSynthesisError(
                            "设备内存不足，已停止继续生成中文配音并恢复视频原声；请重新打开视频或选择占用更低的配音引擎后重试。"
                        )
                    }.onFailure {
                        android.util.Log.w("DubbingEngine", "Unable to publish out-of-memory recovery status", it)
                    }
                }
            }

            // Close the empty-queue race: a cue can be enqueued after pollFirst() returned null
            // but before this Job completes. Publish this worker as idle under the same lock used
            // by startSynthLoop(), then restart if work arrived during that window.
            val completedWorker = kotlinx.coroutines.currentCoroutineContext()[Job]
            val shouldRestart = synchronized(synthStartLock) {
                if (synthJob === completedWorker) synthJob = null
                !released && !synthesisMemoryFailure && !fallbackRequested.get() && pending.isNotEmpty()
            }
            if (shouldRestart) startSynthLoop()
            }
        }
    }

    /**
     * 去掉合成音频首尾的静音（最多各 400ms，保留 25ms 余量），让配音的第一个字贴近字幕开口时间。
     * 已经裁过的引擎（Kokoro/Piper/系统 TTS）再裁一次是空操作；全静音的音频原样保留。
     */
    private fun trimEdgeSilence(
        audio: io.translatetube.local.tts.TtsAudio,
        checkActive: () -> Unit,
    ): io.translatetube.local.tts.TtsAudio {
        val trimmed = io.translatetube.local.tts.TtsEdgeSilenceTrimmer.trim(
            audio.samples,
            audio.sampleRate,
            checkActive,
        )
        return if (trimmed === audio.samples) audio else audio.copy(samples = trimmed)
    }

    /**
     * 把合成结果按窗口压缩（只压不拉，规则见 [DubbingFitPolicy.synthesisRatio]）。
     * 对齐只是后处理：失败就保留已验证的原始 PCM，绝不把合成成功的句子报成 TTS 失败。
     */
    private fun fitAudioToWindow(
        idx: Int,
        audio: io.translatetube.local.tts.TtsAudio,
        windowMs: Long,
    ): io.translatetube.local.tts.TtsAudio {
        val ratio = DubbingFitPolicy.synthesisRatio(audio.samples.size, audio.sampleRate, windowMs)
        if (ratio >= 1f) return audio
        return try {
            audio.copy(samples = Wsola.stretch(audio.samples, ratio))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (alignmentError: Exception) {
            android.util.Log.w(
                "DubbingEngine",
                "WSOLA alignment failed for line ${idx + 1}; preserving original synthesized PCM",
                alignmentError,
            )
            runCatching {
                io.translatetube.local.util.AppLogStore.warning(
                    appContext,
                    "DubbingEngine",
                    "第 ${idx + 1}/${lines.size} 句配音时长对齐失败，已保留成功合成的原始音频",
                    alignmentError,
                    diagnosticContext = mapOf(
                        "字幕行号" to "${idx + 1}/${lines.size}",
                        "配音引擎" to (tts?.name ?: "未知引擎"),
                        "合成音频样本数" to audio.samples.size.toString(),
                        "合成采样率 Hz" to audio.sampleRate.toString(),
                        "目标对齐比例" to ratio.toString(),
                        "恢复策略" to "跳过 WSOLA 变速对齐，缓存并继续使用原始合成 PCM；挂载时由总线排队避免叠音",
                    ),
                )
            }
            audio
        }
    }

    private fun requestTtsFallback(index: Int, detail: String): Boolean {
        if (!tts?.name.orEmpty().contains("系统TTS")) return false
        if (!fallbackRequested.compareAndSet(false, true)) return true
        val accepted = runCatching { onFallbackRequested?.invoke(index, detail) == true }.getOrDefault(false)
        if (!accepted) fallbackRequested.set(false)
        return accepted
    }

    /**
     * Replace a failed System TTS instance after the active synthesis job has unwound, then retry
     * the cue with a fresh engine-specific cache key. Existing valid fallback cache rows are reused.
     */
    suspend fun completeTtsFallback(fallback: TtsEngine, failedIndex: Int) {
        synthJob?.join()
        if (released || failedIndex !in lines.indices) {
            runCatching { fallback.close() }
            fallbackRequested.set(false)
            return
        }
        val previous = tts
        synchronized(cacheWriteLock) {
            if (released) {
                runCatching { fallback.close() }
                fallbackRequested.set(false)
                return
            }
            ttsInstances.addIfAbsent(fallback)
            tts = fallback
            fallbackRequested.set(false)
        }
        if (previous != null && previous !== fallback) {
            runCatching { previous.close() }
            ttsInstances.remove(previous)
        }
        // The memory LRU is keyed by subtitle index for fast lookup, but its entries are
        // identity-bound. Clear all of it on an engine switch and rebuild readiness for the
        // new engine so rows synthesized by the previous engine are not reported ready.
        restartSynthesisForCurrentConfiguration()
    }

    fun failTtsFallback(failedIndex: Int, detail: String) {
        // Keep the gate closed: continuing this worker would repeatedly synthesize with the
        // already-failed System TTS and could make the whole subtitle queue appear stuck.
        fallbackRequested.set(true)
        if (failedIndex in lines.indices) {
            silentCueReasonsByIndex[failedIndex] = "系统 TTS 失败且备用引擎恢复失败：${detail.take(320)}"
            markCuePrepared(failedIndex, hasAudio = false)
            bumpPrepared()
        }
        reportSynthesisError("系统 TTS 配音失败，自动切换备用引擎也未成功：$detail")
    }

    private fun reportSynthesisError(message: String) {
        if (!released && synthesisErrorReported.compareAndSet(false, true)) {
            logDubbingError(
                "配音功能上报终止性故障：$message",
                diagnosticContext = mapOf(
                    "配音引擎名称" to (tts?.name ?: "未知引擎"),
                    "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                    "故障时字幕行号" to if (_currentLine.value >= 0) "${_currentLine.value + 1}/${lines.size}" else "当前没有活动字幕行",
                    "字幕总行数" to lines.size.toString(),
                    "已准备行数" to _preparedCount.value.toString(),
                    "连续就绪前缀行数" to _preparedUntil.value.toString(),
                    "待合成队列行数" to pending.totalSize().toString(),
                    "音频输出模式" to if (usesImmediateTimelinePlayback) "系统 TTS 即时朗读" else "PCM 混流",
                    "首句输出是否已确认" to firstPlaybackReported.get().toString(),
                    "视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                    "混流总线代次" to outputBus.generationToken().toString(),
                ),
            )
            // A UI/notification callback must never terminate the synthesis worker itself.
            runCatching { onSynthesisError?.invoke(message) }
                .onFailure {
                    android.util.Log.w("DubbingEngine", "Synthesis-error callback failed", it)
                }
        }
    }

    private fun recordDubbingFailure(
        index: Int,
        stage: String,
        failure: Throwable,
        extraContext: Map<String, String> = emptyMap(),
    ) {
        val cue = lines.getOrNull(index)
        val rawCue = inputLinesForDiagnostics.getOrNull(index)
        val text = cue?.zh.orEmpty()
        runCatching {
            io.translatetube.local.util.AppLogStore.error(
                appContext,
                "DubbingEngine",
                "第 ${index + 1}/${lines.size} 句配音${stage}失败（${tts?.name ?: "未知引擎"}）",
                failure,
                diagnosticContext = mapOf(
                    "配音引擎名称" to (tts?.name ?: "未知引擎"),
                    "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                    "故障阶段" to stage,
                    "字幕行号" to "${index + 1}/${lines.size}",
                    "字幕字符数" to text.length.toString(),
                    "字幕 Unicode 码点数" to text.codePointCount(0, text.length).toString(),
                    "原字幕内容预览" to rawCue?.text.orEmpty().take(240),
                    "输入译文内容预览" to rawCue?.zh.orEmpty().take(240),
                    "清洗后中文配音文本预览" to text.take(240),
                    "源语言标记" to sourceLanguageForDiagnostics,
                    "该句译文预检原因" to silentCueReasonsByIndex[index].orEmpty().ifBlank { "无预检拒绝原因，进入 TTS 合成后失败" },
                    "字幕时间轴" to (rawCue?.let { "${it.startMs}–${it.endMs} ms" } ?: cue?.let { "${it.startMs}–${it.endMs} ms" } ?: "未知"),
                    "人物/音色编号" to (cue?.speakerId?.toString() ?: "未知"),
                    "配音引擎缓存/模型标识" to runCatching { tts?.cacheKey.orEmpty() }.getOrDefault("读取失败").take(1200),
                    "当前合成语速" to speed.toString(),
                    "音频处理方式" to if (usesImmediateTimelinePlayback) "即时 TTS" else "PCM 合成后播放",
                    "该句额外恢复重试次数" to (synthesisRecoveryRetryCounts[index] ?: 0).toString(),
                    "系统音频模式" to runCatching {
                        (appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)?.mode?.toString() ?: "不可用"
                    }.getOrDefault("读取失败"),
                    "系统音乐播放状态" to runCatching {
                        (appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive?.toString() ?: "不可用"
                    }.getOrDefault("读取失败"),
                    "音频输出设备" to runCatching {
                        val manager = appContext.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                        manager?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                            ?.joinToString { "type=${it.type}, id=${it.id}" }?.take(500)
                            ?.ifBlank { "未检测到输出设备" } ?: "不可用"
                    }.getOrDefault("读取失败"),
                ) + extraContext,
            )
        }.onFailure {
            android.util.Log.w("DubbingEngine", "Could not persist detailed dubbing failure log", it)
        }
    }
    private fun publishReadinessProgress(snapshot: DubbingReadinessProgressSnapshot) {
        _preparedCount.value = snapshot.preparedCount
        _preparedUntil.value = snapshot.preparedUntil
    }

    private fun markCuePrepared(idx: Int, hasAudio: Boolean) {
        readinessProgress.markPrepared(idx, hasAudio, lines.size, ::publishReadinessProgress)
        if (hasAudio) silentCueReasonsByIndex.remove(idx)
        // 块首行完成时同步补标其次行（次行播放重定向到首行 PCM，就绪状态跟随首行）。
        if (chunkRedirect.containsValue(idx)) {
            for ((secondary, firstIndex) in chunkRedirect) {
                if (firstIndex == idx && secondary != idx) {
                    readinessProgress.markPrepared(secondary, hasAudio, lines.size, ::publishReadinessProgress)
                    if (hasAudio) {
                        silentCueReasonsByIndex.remove(secondary)
                    } else {
                        val firstReason = silentCueReasonsByIndex[idx]
                        if (!firstReason.isNullOrBlank()) {
                            silentCueReasonsByIndex[secondary] =
                                "所属合并配音块首句第 ${idx + 1} 行失败：$firstReason"
                        }
                    }
                }
            }
        }
    }

    private fun markCuePending(idx: Int) {
        val affected = DubbingChunkReadinessPolicy.affectedIndexes(idx, chunkRedirect)
        readinessProgress.markPendingGroup(affected, lines.size, ::publishReadinessProgress)
        affected.forEach { silentCueReasonsByIndex.remove(it) }
    }

    private fun bumpPrepared() {
        readinessProgress.refresh(lines.size, ::publishReadinessProgress)
    }

    /**
     * Keep recently used subtitle PCM for quick repeat playback without allowing old videos
     * to grow the app cache forever. The currently attached video is never evicted here.
     */
    private fun pruneOldDubbingCaches() {
        val root = cacheRootDir
        if (!root.isDirectory) return
        // Cache publication and eviction must share one process-wide lock. Otherwise an eviction
        // decision based on an old file listing can delete a freshly installed replacement.
        synchronized(cacheInstallLock) {
            val nowMs = System.currentTimeMillis()
            val staleTemps = root.walkTopDown()
                .filter { file ->
                    isStalePcmTemp(file, nowMs) &&
                        file.parentFile?.absolutePath != cacheDir.absolutePath
                }
                .toList()
            staleTemps.forEach { temp ->
                if (temp.delete()) temp.parentFile?.takeIf { it != root && it.list().isNullOrEmpty() }?.delete()
            }

            val currentSessionCacheNames = lines.indices.map { cacheFile(it).name }.toHashSet()
            val pcmFiles = root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".pcm") }
                .toList()
            val entries = pcmFiles.map { file ->
                DubbingCacheEntry(
                    path = file.absolutePath,
                    bytes = file.length(),
                    lastModified = file.lastModified(),
                    // Only protect files expected by this engine. Old voice/model variants for
                    // the same video stay reusable below budget, but become LRU-evictable if the
                    // global cache grows too large.
                    isCurrentSessionFile = file.parentFile?.absolutePath == cacheDir.absolutePath &&
                        file.name in currentSessionCacheNames,
                )
            }
            for (entry in DubbingCachePolicy.filesToEvict(entries)) {
                val file = File(entry.path)
                if (file.delete()) {
                    file.parentFile?.takeIf { it != root && it.list().isNullOrEmpty() }?.delete()
                }
            }
        }
    }

    private fun cacheFile(idx: Int, cacheSpeed: Float = speed): File {
        val line = lines.getOrNull(idx)
        // 块首行缓存的是"合并后的整块 PCM"：身份必须由合并后的文本和块窗口决定，
        // 这样合并伙伴（下一句译文/时间）变化时旧缓存不会被误用。
        val translated = line?.zh
        val identityText = if (translated.isNullOrBlank()) {
            ""
        } else {
            chunkTextByFirst[idx]?.takeIf { it.isNotBlank() } ?: translated
        }
        val windowEndMs = chunkWindowEndByFirst[idx] ?: line?.endMs ?: 0L
        val cueDurationMs = (windowEndMs - (line?.startMs ?: 0L)).coerceAtLeast(0L)
        return File(
            cacheDir,
            DubbingCacheIdentity.fileName(
                lineIndex = idx,
                speakerId = line?.speakerId ?: -1,
                engineKey = tts?.cacheKey ?: tts?.name ?: "unknown",
                text = identityText,
                speed = cacheSpeed,
                cueDurationMs = cueDurationMs,
            ),
        )
    }

    /** Read only PCM synthesized for the currently active TTS/text/speaker/speed identity. */
    private fun memoryAudio(idx: Int): DubbingPcmMemoryEntry? {
        val activeIdentity = cacheFile(idx).name
        return synchronized(mem) {
            val entry = mem[idx] ?: return@synchronized null
            if (DubbingMemoryCacheIdentityPolicy.canReuse(entry.cacheIdentity, activeIdentity)) {
                entry
            } else {
                // Remove the stale index before it can occupy memory or mask a disk-cache miss.
                mem.remove(idx)
                null
            }
        }
    }

    private fun rememberMemoryAudio(
        idx: Int,
        samples: FloatArray,
        sampleRate: Int,
        cacheIdentity: String,
    ) {
        synchronized(mem) {
            // A cache reader may finish just as this engine is retired. Never repopulate its
            // in-memory PCM LRU after release has begun.
            if (!released) {
                mem[idx] = DubbingPcmMemoryEntry(samples, sampleRate, cacheIdentity)
            }
        }
    }
    private fun validCache(file: File): Boolean = file.isFile && file.length() >= 8L

    /**
     * Delete only if the path still points to the exact file version we inspected.
     * The stamp check and deletion share the publication lock so an atomic replacement
     * cannot be removed by a stale reader.
     */
    private fun deleteCacheIfUnchanged(file: File, stamp: DubbingCacheFileStamp?): Boolean {
        if (stamp == null) return false
        return synchronized(cacheInstallLock) {
            if (!stamp.stillMatches(file)) false else file.delete()
        }
    }

    /**
     * Validate a PCM cache and retain its fingerprint for any follow-up cleanup. Never
     * remove a path based only on an older file descriptor: a different engine may have
     * atomically installed a replacement while this stream was being checked.
     */
    private fun inspectCache(
        file: File,
        checkActive: () -> Unit = {},
    ): DubbingCacheInspection {
        val stamp = DubbingCacheFileStamp.capture(file)
            ?: return DubbingCacheInspection(null, null)
        if (stamp.length < 8L) {
            deleteCacheIfUnchanged(file, stamp)
            return DubbingCacheInspection(null, stamp)
        }
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file))).use { input ->
                val sampleRate = input.readInt()
                val sampleCount = input.readInt()
                if (sampleRate !in 8_000..192_000 || sampleCount < 0 || sampleCount > DubbingPcmValidation.MAX_SAMPLE_COUNT ||
                    stamp.length != 8L + sampleCount.toLong() * 4L
                ) {
                    throw java.io.IOException("PCM 缓存头或长度无效")
                }

                // A non-empty file can still contain only zeroes (or invalid floats). Do not
                // promote it to "ready" on attach; a translated cue with such a cache must
                // be queued for synthesis again instead of silently falling back during playback.
                var hasAudibleSample = false
                repeat(sampleCount) { index ->
                    // Large PCM caches can contain millions of samples. Check cancellation
                    // periodically so replacing a video does not wait for a full cache scan.
                    if ((index and 0x1fff) == 0) checkActive()
                    val sample = input.readFloat()
                    if (!sample.isFinite()) throw java.io.IOException("PCM 缓存包含无效采样值")
                    if (DubbingPcmValidation.isAudibleSample(sample)) hasAudibleSample = true
                }
                when {
                    sampleCount == 0 -> DubbingCacheInspection(false, stamp) // Valid explicit empty marker.
                    hasAudibleSample -> DubbingCacheInspection(true, stamp)
                    else -> throw java.io.IOException("PCM 缓存只有静音采样")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            deleteCacheIfUnchanged(file, stamp)
            DubbingCacheInspection(null, stamp)
        }
    }

    /** Update the file-backed LRU timestamp when a PCM row is actually reused for playback. */
    private fun touchCacheFile(
        file: File,
        expectedStamp: DubbingCacheFileStamp? = DubbingCacheFileStamp.capture(file),
    ) {
        if (expectedStamp == null) return
        runCatching {
            synchronized(cacheInstallLock) {
                // Don't update the LRU timestamp of a new atomic replacement using a stale reader's
                // path. This lock is shared with cache publication and eviction.
                if (expectedStamp.stillMatches(file)) {
                    file.setLastModified(System.currentTimeMillis())
                }
            }
        }
    }

    /** true = valid audio, false = valid empty marker, null = missing or corrupt cache. */
    private fun cached(
        idx: Int,
        checkActive: () -> Unit = {},
    ): Boolean? {
        val file = cacheFile(idx)
        // Memory entries have already been checked against the active engine/text/speaker/speed
        // identity. A fresh in-memory result remains usable even if its disk write failed.
        val inMemory = memoryAudio(idx)
        if (!DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = forceRegeneratePcmForAttach,
                wasWrittenByCurrentRun = file.name in freshlySynthesizedCacheFiles,
                hasMatchingCurrentMemoryEntry = inMemory != null,
            )
        ) {
            checkActive()
            return null
        }
        if (inMemory != null) {
            checkActive()
            return true
        }
        val inspection = inspectCache(file, checkActive)
        val translatedText = lines.getOrNull(idx)?.zh
        val reusable = DubbingCacheAudioPolicy.reusableState(
            inspection.hasAudio,
            translatedText,
        )
        val cachedAudio = inspection.hasAudio
        if (
            cachedAudio != null &&
            !DubbingCacheAudioPolicy.isCompatibleWithTranslation(cachedAudio, translatedText)
        ) {
            // A file may be replaced after attach() inspected it. Recheck the audio/text contract
            // on every cache hit so stale silence or stale speech cannot become permanently ready.
            deleteCacheIfUnchanged(file, inspection.stamp)
            return null
        }
        return reusable
    }

    /**
     * A cache can become unavailable after attach() has marked its row ready. Requeue it without
     * starting a second synthesis worker. Joining the worker observed here closes the small race
     * where it has polled an empty queue but has not yet completed its Job.
     */
    private fun queueSynthesisRetry(idx: Int) {
        val retryIndex = resolveChunkIndex(idx)
        if (released || retryIndex !in lines.indices) return
        var ensureExistingQueueWorker = false
        val shouldScheduleWorker = synchronized(pending) {
            when (
                DubbingRetryQueuePolicy.decide(
                    index = retryIndex,
                    activeIndex = activeSynthesisIndex,
                    alreadyQueued = pending.contains(retryIndex),
                )
            ) {
                DubbingRetryQueueDecision.IGNORE -> false
                DubbingRetryQueueDecision.DEFER_UNTIL_ACTIVE_CUE_FINISHES -> {
                    retryAfterActiveSynthesis.add(retryIndex)
                    false
                }
                DubbingRetryQueueDecision.ENSURE_WORKER -> {
                    ensureExistingQueueWorker = true
                    false
                }
                DubbingRetryQueueDecision.ENQUEUE -> {
                    pending.offerFirst(retryIndex)
                    true
                }
            }
        }
        if (ensureExistingQueueWorker) {
            // The cue is already queued, but the worker may have exited just before this request.
            // startSynthLoop() is idempotent while a worker remains active.
            startSynthLoop()
            return
        }
        if (!shouldScheduleWorker) return
        val workerAtSchedule = synthJob
        scope.launch {
            workerAtSchedule?.join()
            if (!released && pending.isNotEmpty()) startSynthLoop()
        }
    }

    /** 原子写入缓存，防止进程中断留下半个 PCM 文件并被误判为已就绪。 */
    private fun writeCache(
        idx: Int,
        sampleRate: Int,
        samples: FloatArray,
        cacheSpeed: Float = speed,
        checkActive: () -> Unit = {},
    ): String {
        val target = cacheFile(idx, cacheSpeed)
        val temp = File(cacheDir, DubbingCacheIdentity.tempFileName(target.name, cacheWriterId))
        temp.delete()
        try {
            checkActive()
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(temp))).use { out ->
                out.writeInt(sampleRate)
                out.writeInt(samples.size)
                for (index in samples.indices) {
                    // Large subtitle cues can contain millions of samples. Do not keep writing
                    // stale PCM after a seek or video switch cancels the synthesis worker.
                    if ((index and 0x1fff) == 0) checkActive()
                    out.writeFloat(samples[index])
                }
                out.flush()
            }
            // Subtitle PCM is a disposable derived cache, not user data. Closing the buffered
            // stream above flushes the writes to the OS; forcing a storage fsync for every cue adds
            // a costly flash-storage barrier on older phones. Publish atomically and validate the
            // cache length on read; an interrupted/corrupt cache is discarded and synthesized again.
            checkActive()
            if (!validCache(temp) || temp.length() != 8L + samples.size.toLong() * 4L) {
                throw java.io.IOException("PCM 缓存写入不完整")
            }
            synchronized(cacheInstallLock) {
                checkActive()
                synchronized(cacheWriteLock) {
                    if (released) {
                        throw kotlinx.coroutines.CancellationException("配音引擎已释放，取消缓存写入")
                    }
                    // Serialize publication across old/new engine instances. Each writer has a
                    // unique staging path so a retired writer cannot rename another writer's temp.
                    if (!temp.renameTo(target)) {
                        throw java.io.IOException("无法原子替换 PCM 缓存；旧缓存已保留")
                    }
                    if (forceRegeneratePcmForAttach) {
                        freshlySynthesizedCacheFiles.add(target.name)
                    }
                }
            }
        } catch (error: Exception) {
            // A cancelled or failed write must not leave a partial file in the active cache dir.
            runCatching { temp.delete() }
            throw error
        }
        return target.name
    }

    /** 返回 (samples, sampleRate)；严格校验长度，避免损坏缓存导致超大分配或崩溃。
     *  块次行自动重定向到首行（整块 PCM 挂在首行名下）。 */
    private fun load(
        idx: Int,
        isCurrent: () -> Boolean = { true },
    ): Pair<FloatArray, Int>? {
        val expectedCacheGeneration = synchronized(cacheConfigurationLock) {
            cacheInitGeneration.get()
        }
        fun isReadCurrent(): Boolean =
            isCurrent() && isCacheConfigurationCurrent(expectedCacheGeneration)

        if (!isReadCurrent()) return null
        val playIdx = resolveChunkIndex(idx)
        val f = cacheFile(playIdx)
        val inMemory = memoryAudio(playIdx)
        // Disk hits must belong to this forced run. A matching current memory entry is fresh
        // independently: it can be the only usable copy after a disk-write failure.
        if (!DubbingCacheFreshnessPolicy.canReadCache(
                forceFreshSynthesis = forceRegeneratePcmForAttach,
                wasWrittenByCurrentRun = f.name in freshlySynthesizedCacheFiles,
                hasMatchingCurrentMemoryEntry = inMemory != null,
            )
        ) {
            if (!isReadCurrent()) return null
            markCuePending(playIdx)
            queueSynthesisRetry(playIdx)
            return null
        }
        if (inMemory != null) {
            if (!isReadCurrent()) return null
            // Media3's LRU evictor tracks actual cache touches. Keep our file-backed cache's
            // mtime aligned when playback hits memory so frequently reused PCM isn't evicted as
            // if it had not been used since the original synthesis.
            touchCacheFile(f)
            return inMemory.samples to inMemory.sampleRate
        }
        val cacheStamp = DubbingCacheFileStamp.capture(f)
        if (!validCache(f)) {
            // A newer seek/session owns the player now; do not delete or requeue a cache on its behalf.
            if (!isReadCurrent()) return null
            // Cache files can disappear after attach() validates them. Treat this as recoverable
            // pending work, not a permanent silent cue; keep source audio audible while regenerating.
            deleteCacheIfUnchanged(f, cacheStamp)
            markCuePending(playIdx)
            queueSynthesisRetry(playIdx)
            return null
        }
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { d ->
                val sr = d.readInt()
                val n = d.readInt()
                if (sr !in 8_000..192_000 || n < 0 || n > DubbingPcmValidation.MAX_SAMPLE_COUNT ||
                    f.length() != 8L + n.toLong() * 4L) {
                    throw java.io.IOException("PCM 缓存格式或采样率无效")
                }
                // A seek/session replacement may happen while opening and validating the
                // cache header. Recheck before reserving up to 64 MB for stale PCM.
                if (!isReadCurrent()) return null
                val samples = FloatArray(n)
                var hasAudibleSample = false
                for (sampleIndex in 0 until n) {
                    // PCM may contain millions of samples. Cancel stale playback loads in bounded
                    // chunks so a seek doesn't wait for an entire large file to be materialized.
                    if ((sampleIndex and 0x1fff) == 0 && !isReadCurrent()) return null
                    val sample = d.readFloat()
                    if (!sample.isFinite()) throw java.io.IOException("PCM 缓存包含无效采样值")
                    if (!hasAudibleSample && DubbingPcmValidation.isAudibleSample(sample)) {
                        hasAudibleSample = true
                    }
                    samples[sampleIndex] = sample
                }
                if (!isReadCurrent()) return null
                if (n > 0 && !hasAudibleSample) {
                    throw java.io.IOException("PCM 缓存包含静音音频")
                }
                val hasAudio = n > 0
                if (!DubbingCacheAudioPolicy.isCompatibleWithTranslation(
                        hasAudio = hasAudio,
                        translatedText = lines.getOrNull(playIdx)?.zh,
                    )
                ) {
                    // A stale empty marker must not suppress speech for a translated cue, and
                    // a non-empty cache must not override a deliberately blank translation.
                    throw java.io.IOException("PCM 缓存音频状态与字幕译文不一致")
                }
                // 合并块的文本与窗口已经是缓存标识的一部分（见 cacheFile），合并关系变化时旧缓存
                // 自然不会命中，不再需要按时长猜测缓存是否"串台"。够放的句子保持自然时长，
                // 比窗口短是正常的，按时长校验反而会把有效缓存当成污染、无限重合成。
                if (!hasAudio) {
                    markCuePrepared(playIdx, hasAudio = false)
                } else {
                    rememberMemoryAudio(playIdx, samples, sr, f.name)
                    markCuePrepared(playIdx, hasAudio = true)
                }
                touchCacheFile(f, cacheStamp)
                samples to sr
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A seek can invalidate the reader while the file is being read. That is not cache
            // corruption; leave the file untouched and let the next playAt() load its own cue.
            if (!isReadCurrent()) return null
            // A corrupt cache is recoverable too. Roll back displayed progress and leave the cue
            // pending so it can be synthesized again instead of being treated as permanently silent.
            deleteCacheIfUnchanged(f, cacheStamp)
            // The file was resolved through the merged chunk's first row. Invalidate and
            // requeue that same canonical owner, not the secondary row that requested playback.
            markCuePending(playIdx)
            queueSynthesisRetry(playIdx)
            null
        }
    }

    /** Continue partial writes and tolerate a few transient zero-progress responses from OEM audio sinks. */
    /**
     * An asynchronous playAt() request may become obsolete before its audio starts. Clear only
     * its own not-yet-started row: never overwrite a newer generation or a successfully started cue.
     */
    private fun clearUnstartedLineIfCurrent(idx: Int, generation: Int) {
        synchronized(playbackControlLock) {
            if (!released &&
                playbackGeneration.get() == generation &&
                _currentLine.value == idx &&
                playbackStartedLine != idx
            ) {
                _currentLine.value = -1
            }
        }
    }

    private fun writeFully(track: AudioTrack, samples: FloatArray): Int =
        AudioTrackWritePolicy.writeFully(
            sampleCount = samples.size,
            write = { offset, remaining ->
                track.write(samples, offset, remaining, AudioTrack.WRITE_BLOCKING)
            },
        )

    private fun writeFully(track: AudioTrack, samples: ShortArray): Int =
        AudioTrackWritePolicy.writeFully(
            sampleCount = samples.size,
            write = { offset, remaining ->
                track.write(samples, offset, remaining, AudioTrack.WRITE_BLOCKING)
            },
        )

    /**
     * Float PCM is preferred for fidelity, but some OEM audio stacks fail to initialize or write
     * PCM_FLOAT tracks. Retry once using the broadly supported PCM_16BIT format before reporting
     * a dubbing-output failure, so a valid TTS result is not discarded on older devices.
     */
    private fun nextStaticChunkEnd(startSample: Int, totalSamples: Int): Int =
        (startSample.toLong() + MAX_STATIC_TRACK_SAMPLES).coerceAtMost(totalSamples.toLong()).toInt()

    /**
     * The marker now fires before a static buffer ends. Keep the source audible while its next
     * chunk is built, then commit only at the old buffer boundary. Playback generation invalidation
     * makes pause/seek/release break this wait promptly without tying up the UI thread.
     */
    private fun staticChunkBoundaryTimeoutMs(
        expectedSampleCount: Long,
        sampleRate: Int,
        playbackRate: Float,
    ): Long {
        val safeSampleRate = sampleRate.coerceIn(8_000, 192_000)
        // A requested speed-up may be unsupported by an OEM audio sink, so estimate at no
        // faster than 1x. Slow playback must extend the deadline instead of failing at 5 seconds.
        val safePlaybackRate = playbackRate
            .takeIf { it.isFinite() && it > 0.0f }
            ?.coerceIn(0.1f, 1.0f)
            ?: 1.0f
        val expectedPlaybackMs = kotlin.math.ceil(
            expectedSampleCount.toDouble() * 1_000.0 /
                safeSampleRate.toDouble() / safePlaybackRate.toDouble(),
        ).toLong()
        return maxOf(
            MIN_STATIC_CHUNK_BOUNDARY_TIMEOUT_MS,
            expectedPlaybackMs + STATIC_CHUNK_BOUNDARY_TIMEOUT_GRACE_MS,
        )
    }

    private fun awaitStaticChunkBoundary(
        sourceTrack: AudioTrack,
        chunkStartSample: Int,
        chunkEndSample: Int,
        sampleRate: Int,
        generation: Int,
    ): Boolean {
        val expectedSampleCount = (chunkEndSample - chunkStartSample).coerceAtLeast(0).toLong()
        val waitStartedMs = android.os.SystemClock.elapsedRealtime()
        var timeoutMs = staticChunkBoundaryTimeoutMs(expectedSampleCount, sampleRate, activePlaybackRate)
        var lastObservedHead = -1L
        var lastHeadProgressAtMs = waitStartedMs
        while (true) {
            if (!DubbingChunkTransitionPolicy.canCommit(
                    released = released,
                    currentGeneration = playbackGeneration.get(),
                    expectedGeneration = generation,
                    currentTrackIsSource = track === sourceTrack,
                )
            ) return false

            val nowMs = android.os.SystemClock.elapsedRealtime()
            val playedSamples = sourceTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (playedSamples >= expectedSampleCount ||
                sourceTrack.playState != AudioTrack.PLAYSTATE_PLAYING
            ) return true

            if (playedSamples > lastObservedHead) {
                lastObservedHead = playedSamples
                lastHeadProgressAtMs = nowMs
            }
            // Playback speed can change while a long cue is active. Extend the deadline when
            // necessary, but never shorten a wait that is already in progress.
            val updatedTimeoutMs = staticChunkBoundaryTimeoutMs(
                expectedSampleCount,
                sampleRate,
                activePlaybackRate,
            )
            if (updatedTimeoutMs > timeoutMs) timeoutMs = updatedTimeoutMs

            val elapsedMs = nowMs - waitStartedMs
            val headStallMs = nowMs - lastHeadProgressAtMs
            if (headStallMs >= STATIC_CHUNK_HEAD_STALL_TIMEOUT_MS || elapsedMs >= timeoutMs) {
                // Several Android 9/OEM static AudioTrack sinks stop reporting head progress
                // within the final few milliseconds of a buffer while playState remains PLAYING.
                // The next chunk has already been prefetched; when only a tiny tail remains,
                // advance to it rather than discard the entire synthesized sentence.
                if (DubbingChunkTransitionPolicy.canAdvanceAfterNearEndStall(
                        expectedSampleCount = expectedSampleCount,
                        playedSampleCount = playedSamples,
                        sampleRate = sampleRate,
                    )
                ) {
                    val remainingSamples = (expectedSampleCount - playedSamples).coerceAtLeast(0L)
                    android.util.Log.w(
                        "DubbingEngine",
                        "AudioTrack 播放头在分段末尾停滞，按边界继续后续配音分段：" +
                            "已播放=$playedSamples/$expectedSampleCount，剩余采样点=$remainingSamples，" +
                            "采样率=${sampleRate}Hz，播放头停滞=${headStallMs}ms",
                    )
                    return true
                }
                val reason = if (headStallMs >= STATIC_CHUNK_HEAD_STALL_TIMEOUT_MS) {
                    "播放头长时间没有前进"
                } else {
                    "等待时间超过本段预计播放窗口"
                }
                val errorMessage =
                    "等待配音分段结束超时：原因=$reason；期望采样点=$expectedSampleCount，" +
                        "已播放采样点=$playedSamples，采样率=${sampleRate}Hz，播放速率=$activePlaybackRate，" +
                        "音轨状态=${sourceTrack.playState}，已等待=${elapsedMs}ms，预算=${timeoutMs}ms，" +
                        "播放头停滞=${headStallMs}ms"
                android.util.Log.w("DubbingEngine", errorMessage)
                throw DubbingChunkBoundaryTimeoutException(errorMessage)
            }
            android.os.SystemClock.sleep(2L)
        }
    }

    /**
     * Schedules the next bounded static buffer only while the same video cue still owns playback.
     * The callback is marshalled to the audio executor before touching shared AudioTrack state.
     */
    private fun installNextStaticChunk(
        sourceTrack: AudioTrack,
        samples: FloatArray,
        sampleRate: Int,
        chunkStartSample: Int,
        chunkEndSample: Int,
        lineIndex: Int,
        generation: Int,
        preparedAtMs: Long,
        syncRate: Float,
        lineStartMs: Long,
        lineEndMs: Long,
        completeCueWhenLate: Boolean = false,
        initialHeadPosition: Int = 0,
    ) {
        if (chunkEndSample <= chunkStartSample) return
        val chunkSampleCount = chunkEndSample - chunkStartSample
        if (chunkEndSample >= samples.size) {
            sourceTrack.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(callbackTrack: AudioTrack) {
                        if (callbackTrack !== sourceTrack) return
                        try {
                            audioExec.execute {
                                if (released || playbackGeneration.get() != generation || track !== sourceTrack) {
                                    return@execute
                                }
                                synchronized(playbackControlLock) {
                                    if (playbackGeneration.get() == generation && _currentLine.value == lineIndex) {
                                        activeCueOverrunsWindow = false
                                        activeTrackFinalChunkSampleCount = -1
                                    }
                                }
                            }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            // The next player tick also checks the final static buffer's playback head.
                        }
                    }

                    override fun onPeriodicNotification(callbackTrack: AudioTrack) = Unit
                },
                Handler(Looper.getMainLooper()),
            )
            val result = sourceTrack.setNotificationMarkerPosition(chunkSampleCount)
            if (result != AudioTrack.SUCCESS) {
                android.util.Log.w(
                    "DubbingEngine",
                    "OEM rejected final cue completion marker (status=$result); playback-head polling remains enabled",
                )
            }
            return
        }
        val markerCandidates = DubbingChunkTransitionPolicy.markerPositionCandidates(
            chunkSampleCount = chunkSampleCount,
            sampleRate = sampleRate,
            playbackRate = activePlaybackRate,
            currentHeadPosition = initialHeadPosition,
        )
        sourceTrack.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(callbackTrack: AudioTrack) {
                    if (callbackTrack !== sourceTrack) return
                    try {
                        audioExec.execute {
                            if (!DubbingChunkTransitionPolicy.canCommit(
                                    released = released,
                                    currentGeneration = playbackGeneration.get(),
                                    expectedGeneration = generation,
                                    currentTrackIsSource = track === sourceTrack,
                                )
                            ) return@execute
                            var createdNextTrack: AudioTrack? = null
                            try {
                                if (!DubbingChunkTransitionPolicy.canCommit(
                                        released = released,
                                        currentGeneration = playbackGeneration.get(),
                                        expectedGeneration = generation,
                                        currentTrackIsSource = track === sourceTrack,
                                    )
                                ) return@execute

                                // Prepare the next static buffer during the final part of the
                                // current one instead of waiting for an end marker and then paying
                                // the full AudioTrack creation/write latency.
                                var nextChunk = createStaticChunkWithFallback(
                                    samples = samples,
                                    startSample = chunkEndSample,
                                    requestedEndSample = nextStaticChunkEnd(chunkEndSample, samples.size),
                                    sampleRate = sampleRate,
                                )
                                createdNextTrack = nextChunk.track
                                if (!awaitStaticChunkBoundary(
                                        sourceTrack,
                                        chunkStartSample,
                                        chunkEndSample,
                                        sampleRate,
                                        generation,
                                    )
                                ) {
                                    runCatching { createdNextTrack?.release() }
                                    createdNextTrack = null
                                    return@execute
                                }
                                if (!DubbingChunkTransitionPolicy.canCommit(
                                        released = released,
                                        currentGeneration = playbackGeneration.get(),
                                        expectedGeneration = generation,
                                        currentTrackIsSource = track === sourceTrack,
                                    )
                                ) {
                                    runCatching { createdNextTrack?.release() }
                                    createdNextTrack = null
                                    return@execute
                                }

                                // Re-read the playhead after prefetching. The source can advance
                                // while AudioTrack initializes, so a cue that is now further ahead
                                // must skip stale samples instead of replaying delayed speech.
                                val targetSkip = if (completeCueWhenLate) {
                                    0
                                } else {
                                    val latestPosition = DubbingStartPositionPolicy.choose(
                                        lineIndex = lineIndex,
                                        lineStartMs = lineStartMs,
                                        lineEndMs = lineEndMs,
                                        requestedPositionMs = preparedAtMs,
                                        latestCursor = latestTimelineCursor,
                                    )
                                    if (latestPosition == null) {
                                        if (track === sourceTrack) track = null
                                        runCatching { sourceTrack.release() }
                                        runCatching { createdNextTrack?.release() }
                                        createdNextTrack = null
                                        return@execute
                                    }
                                    DubbingTiming.lateStartSkipSamples(
                                        preparedAtMs = preparedAtMs,
                                        currentPositionMs = latestPosition,
                                        syncRate = syncRate,
                                        sampleRate = sampleRate,
                                        availableSampleCount = samples.size,
                                    )
                                }
                                val nextStart = if (completeCueWhenLate) chunkEndSample else maxOf(chunkEndSample, targetSkip)
                                if (nextStart >= samples.size) {
                                    if (track === sourceTrack) track = null
                                    runCatching { sourceTrack.release() }
                                    runCatching { createdNextTrack?.release() }
                                    createdNextTrack = null
                                    return@execute
                                }

                                var headOffset = (nextStart - nextChunk.startSample).coerceAtLeast(0)
                                if (nextStart >= nextChunk.endSample) {
                                    // The playhead overran the prefetched range while the track
                                    // was being created. Rebuild from the current position, not
                                    // from an expired chunk that would replay or immediately end.
                                    runCatching { createdNextTrack?.release() }
                                    createdNextTrack = null
                                    nextChunk = createStaticChunkWithFallback(
                                        samples = samples,
                                        startSample = nextStart,
                                        requestedEndSample = nextStaticChunkEnd(nextStart, samples.size),
                                        sampleRate = sampleRate,
                                    )
                                    createdNextTrack = nextChunk.track
                                    headOffset = 0
                                } else if (headOffset > 0) {
                                    val seekResult = nextChunk.track.setPlaybackHeadPosition(headOffset)
                                    if (seekResult != AudioTrack.SUCCESS) {
                                        throw IllegalStateException(
                                            "无法跳过已过期的配音分段：AudioTrack 状态 $seekResult"
                                        )
                                    }
                                }

                                if (!DubbingChunkTransitionPolicy.canCommit(
                                        released = released,
                                        currentGeneration = playbackGeneration.get(),
                                        expectedGeneration = generation,
                                        currentTrackIsSource = track === sourceTrack,
                                    )
                                ) {
                                    runCatching { createdNextTrack?.release() }
                                    createdNextTrack = null
                                    return@execute
                                }
                                val nextTrack = createdNextTrack
                                    ?: throw IllegalStateException("配音分段音轨创建失败")
                                nextTrack.setVolume(activeVolume.coerceIn(0.0f, 1.0f))
                                runCatching {
                                    nextTrack.setPlaybackParams(
                                        PlaybackParams().setSpeed(activePlaybackRate).setPitch(1.0f)
                                    )
                                }.onFailure {
                                    android.util.Log.w("DubbingEngine", "Could not update next chunk playback rate", it)
                                }
                                installNextStaticChunk(
                                    sourceTrack = nextTrack,
                                    samples = samples,
                                    sampleRate = sampleRate,
                                    chunkStartSample = nextChunk.startSample,
                                    chunkEndSample = nextChunk.endSample,
                                    lineIndex = lineIndex,
                                    generation = generation,
                                    preparedAtMs = preparedAtMs,
                                    syncRate = syncRate,
                                    lineStartMs = lineStartMs,
                                    lineEndMs = lineEndMs,
                                    completeCueWhenLate = completeCueWhenLate,
                                    initialHeadPosition = headOffset,
                                )
                                track = nextTrack
                                activeTrackFinalChunkSampleCount = if (nextChunk.endSample >= samples.size) {
                                    nextChunk.endSample - nextChunk.startSample
                                } else -1
                                runCatching { sourceTrack.release() }
                                if (!startTrackIfCurrent(nextTrack, generation)) {
                                    throw kotlinx.coroutines.CancellationException(
                                        "配音分段已被暂停、拖动进度或新播放任务取代"
                                    )
                                }
                                // Ownership is now published through track; don't release it on
                                // the successful path when the catch block later runs for another reason.
                                createdNextTrack = null
                            } catch (error: Exception) {
                                val failedNextTrack = createdNextTrack
                                if (failedNextTrack != null && track === failedNextTrack) track = null
                                runCatching { failedNextTrack?.release() }
                                createdNextTrack = null

                                // A failed prefetch must not cut the source chunk short. Let the
                                // already-buffered tail play out before declaring the dubbing sink
                                // failed; cancel immediately if pause/seek replaced this generation.
                                if (track === sourceTrack &&
                                    !released && playbackGeneration.get() == generation
                                ) {
                                    val reachedBoundary = if (error is DubbingChunkBoundaryTimeoutException) {
                                        false
                                    } else {
                                        runCatching {
                                            awaitStaticChunkBoundary(
                                                sourceTrack,
                                                chunkStartSample,
                                                chunkEndSample,
                                                sampleRate,
                                                generation,
                                            )
                                        }.getOrDefault(false)
                                    }
                                    if (!reachedBoundary && error !is DubbingChunkBoundaryTimeoutException) {
                                        return@execute
                                    }
                                    if (track === sourceTrack) track = null
                                    runCatching { sourceTrack.release() }
                                }

                                // A rapid seek/pause can invalidate this marker callback while a
                                // following static chunk is being constructed. It is not a real
                                // output failure and must not trigger fallback or UI error state.
                                if (released || playbackGeneration.get() != generation ||
                                    error is kotlinx.coroutines.CancellationException
                                ) return@execute
                                activeCueOverrunsWindow = false
                                activeTrackFinalChunkSampleCount = -1
                                android.util.Log.w("DubbingEngine", "Unable to advance long dubbing cue", error)
                                recordDubbingFailure(
                                    lineIndex,
                                    "长音频分段播放",
                                    error,
                                    extraContext = mapOf(
                                        "分段起始采样点" to chunkStartSample.toString(),
                                        "分段结束采样点" to chunkEndSample.toString(),
                                        "分段采样点数" to (chunkEndSample - chunkStartSample).coerceAtLeast(0).toString(),
                                        "完整配音采样点数" to samples.size.toString(),
                                        "采样率 Hz" to sampleRate.toString(),
                                        "当前播放速率" to activePlaybackRate.toString(),
                                        "播放代次" to generation.toString(),
                                        "当前播放代次" to playbackGeneration.get().toString(),
                                        "失败音轨播放状态" to runCatching { sourceTrack.playState.toString() }.getOrDefault("读取失败"),
                                        "失败音轨播放头" to runCatching { sourceTrack.playbackHeadPosition.toString() }.getOrDefault("读取失败"),
                                        "失败音轨仍由当前配音持有" to (track === sourceTrack).toString(),
                                        "分段输出模式" to "AudioTrack.MODE_STATIC",
                                    ),
                                )
                                val detail = error.message ?: error.javaClass.simpleName
                                markCuePrepared(lineIndex, hasAudio = false)
                                if (!requestTtsFallback(lineIndex, detail)) {
                                    reportSynthesisError("第 ${lineIndex + 1} 句长音频分段播放失败：$detail")
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        // The cleanup barrier owns the track when the audio executor is closing.
                    }
                }

                override fun onPeriodicNotification(callbackTrack: AudioTrack) = Unit
            },
            Handler(Looper.getMainLooper()),
        )
        var lastMarkerResult = AudioTrack.ERROR
        for ((candidateIndex, markerPosition) in markerCandidates.withIndex()) {
            lastMarkerResult = sourceTrack.setNotificationMarkerPosition(markerPosition)
            if (lastMarkerResult == AudioTrack.SUCCESS) {
                if (candidateIndex > 0) {
                    android.util.Log.w(
                        "DubbingEngine",
                        "OEM rejected early dubbing marker; falling back to end marker at $markerPosition",
                    )
                }
                return
            }
            android.util.Log.w(
                "DubbingEngine",
                "AudioTrack rejected dubbing marker $markerPosition (status=$lastMarkerResult)",
            )
        }
        throw IllegalStateException("无法注册配音分段切换标记：AudioTrack 状态 $lastMarkerResult")
    }

    private data class CreatedStaticChunk(
        val track: AudioTrack,
        val startSample: Int,
        val endSample: Int,
    )

    /**
     * If this device rejects a large static buffer in every PCM encoding, retry shorter chunks.
     * The actual end is returned because marker callbacks must match samples installed in the track.
     */
    private fun createStaticChunkWithFallback(
        samples: FloatArray,
        startSample: Int,
        requestedEndSample: Int,
        sampleRate: Int,
    ): CreatedStaticChunk {
        require(startSample >= 0 && requestedEndSample > startSample &&
            requestedEndSample <= samples.size
        ) { "配音静态缓冲区范围无效" }
        val requestedCount = requestedEndSample - startSample
        val candidates = AudioTrackChunkPolicy.candidateSampleCounts(requestedCount)
        var lastError: Exception? = null
        for (count in candidates) {
            val endSample = startSample + count
            try {
                val createdTrack = createFilledAudioTrack(
                    samples.copyOfRange(startSample, endSample),
                    sampleRate,
                )
                if (count != requestedCount) {
                    android.util.Log.w(
                        "DubbingEngine",
                        "AudioTrack accepted reduced static buffer: requested=$requestedCount, actual=$count",
                    )
                }
                return CreatedStaticChunk(createdTrack, startSample, endSample)
            } catch (error: Exception) {
                // Report the actual final fallback attempt, not the first/largest-buffer
                // failure. Keep earlier failures attached for log diagnostics.
                lastError?.let(error::addSuppressed)
                lastError = error
                android.util.Log.w(
                    "DubbingEngine",
                    "Static AudioTrack failed for $count samples at ${sampleRate}Hz; trying smaller buffer",
                    error,
                )
            }
        }
        logDubbingError(
            "设备无法创建任何候选配音静态音轨分段",
            lastError,
            mapOf(
                "请求音轨总样本数" to requestedCount.toString(),
                "采样率 Hz" to sampleRate.toString(),
                "尝试的静态分段样本数" to candidates.joinToString(","),
                "最终失败原因" to describeAudioTrackFailure(lastError),
                "设备" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "Android API" to android.os.Build.VERSION.SDK_INT.toString(),
            ),
        )
        throw IllegalStateException(
            AudioTrackChunkPolicy.failureMessage(sampleRate, candidates, lastError?.message),
            lastError,
        )
    }

    private fun describeAudioTrackFailure(error: Throwable?): String {
        if (error == null) return "系统未提供详细原因"
        val main = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        val cause = error.cause?.takeIf { it !== error }
        val causeMessage = cause?.let { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
        return if (causeMessage == null || main.contains(causeMessage)) {
            main.take(140)
        } else {
            "$main；原因：$causeMessage".take(140)
        }
    }

    private fun createFilledAudioTrack(samples: FloatArray, sampleRate: Int): AudioTrack {
        require(samples.isNotEmpty()) { "配音音频为空" }
        require(samples.size <= MAX_STATIC_TRACK_SAMPLES) {
            "单个 AudioTrack 分段超过安全长度：${samples.size} 个采样点"
        }
        fun attributes() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        var floatTrack: AudioTrack? = null
        var floatFailure: Exception? = null
        try {
            floatTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()

            if (floatTrack.state == AudioTrack.STATE_UNINITIALIZED) {
                throw IllegalStateException(
                    "PCM_FLOAT AudioTrack 初始化失败，state=${floatTrack.state}"
                )
            }
            val written = writeFully(floatTrack, samples)
            if (written != samples.size) {
                throw IllegalStateException(
                    "PCM_FLOAT AudioTrack 写入不完整：$written/${samples.size} 个采样点"
                )
            }
            if (floatTrack.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "PCM_FLOAT AudioTrack 写入后仍未就绪，state=${floatTrack.state}"
                )
            }
            if (audioOutputPathReported.compareAndSet(false, true)) {
                logDubbingInfo(
                    "配音静态音轨初始化成功",
                    mapOf(
                        "实际 PCM 编码" to "PCM_FLOAT",
                        "采样率 Hz" to sampleRate.toString(),
                        "采样点数" to samples.size.toString(),
                        "请求缓冲区 bytes" to (samples.size * 4).toString(),
                        "AudioTrack 状态" to floatTrack.state.toString(),
                        "设备" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                        "Android API" to android.os.Build.VERSION.SDK_INT.toString(),
                    ),
                )
            }
            return floatTrack
        } catch (e: Exception) {
            floatFailure = e
            android.util.Log.w("DubbingEngine", "PCM_FLOAT output unavailable; trying PCM_16BIT", e)
        }
        try { floatTrack?.release() } catch (_: Exception) {}

        var pcm16Track: AudioTrack? = null
        var pcm16Failure: Exception? = null
        try {
            val pcm16 = DubbingPcm16Converter.convert(samples)
            pcm16Track = AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm16.size * 2)
                .build()
            if (pcm16Track.state == AudioTrack.STATE_UNINITIALIZED) {
                throw IllegalStateException(
                    "PCM_16BIT AudioTrack 初始化失败，state=${pcm16Track.state}"
                )
            }
            val written = writeFully(pcm16Track, pcm16)
            if (written != pcm16.size) {
                throw IllegalStateException(
                    "PCM_16BIT AudioTrack 写入不完整：$written/${pcm16.size} 个采样点"
                )
            }
            if (pcm16Track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "PCM_16BIT AudioTrack 写入后仍未就绪，state=${pcm16Track.state}"
                )
            }
            android.util.Log.i(
                "DubbingEngine",
                "Using PCM_16BIT fallback for ${samples.size} samples at ${sampleRate} Hz",
            )
            if (audioOutputPathReported.compareAndSet(false, true)) {
                logDubbingInfo(
                    "配音静态音轨已回退到 PCM_16BIT",
                    mapOf(
                        "实际 PCM 编码" to "PCM_16BIT",
                        "采样率 Hz" to sampleRate.toString(),
                        "采样点数" to pcm16.size.toString(),
                        "请求缓冲区 bytes" to (pcm16.size * 2).toString(),
                        "PCM_FLOAT 失败原因" to describeAudioTrackFailure(floatFailure),
                        "AudioTrack 状态" to pcm16Track.state.toString(),
                        "设备" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                        "Android API" to android.os.Build.VERSION.SDK_INT.toString(),
                    ),
                )
            }
            return pcm16Track
        } catch (pcm16Error: Exception) {
            pcm16Failure = pcm16Error
            try { pcm16Track?.release() } catch (_: Exception) {}
            // Android 9/OEM audio stacks can reject Builder-configured static tracks even when
            // the same PCM format is supported. Retry through the legacy constructor as well.
            var legacyTrack: AudioTrack? = null
            try {
                val pcm16 = DubbingPcm16Converter.convert(samples)
                legacyTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    pcm16.size * 2,
                    AudioTrack.MODE_STATIC,
                )
                if (legacyTrack.state == AudioTrack.STATE_UNINITIALIZED) {
                    throw IllegalStateException(
                        "传统 PCM_16BIT AudioTrack 初始化失败，state=${legacyTrack.state}"
                    )
                }
                val written = writeFully(legacyTrack, pcm16)
                if (written != pcm16.size) {
                    throw IllegalStateException(
                        "传统 PCM_16BIT AudioTrack 写入不完整：$written/${pcm16.size} 个采样点"
                    )
                }
                if (legacyTrack.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException(
                        "传统 PCM_16BIT AudioTrack 写入后仍未就绪，state=${legacyTrack.state}"
                    )
                }
                android.util.Log.i("DubbingEngine", "Using legacy PCM_16BIT AudioTrack fallback")
                if (audioOutputPathReported.compareAndSet(false, true)) {
                    logDubbingInfo(
                        "配音静态音轨已回退到传统 AudioTrack 构造方式",
                        mapOf(
                            "实际 PCM 编码" to "PCM_16BIT",
                            "实际构造方式" to "传统 AudioTrack 构造函数",
                            "采样率 Hz" to sampleRate.toString(),
                            "采样点数" to pcm16.size.toString(),
                            "请求缓冲区 bytes" to (pcm16.size * 2).toString(),
                            "PCM_FLOAT 失败原因" to describeAudioTrackFailure(floatFailure),
                            "Builder PCM_16BIT 失败原因" to describeAudioTrackFailure(pcm16Failure),
                            "AudioTrack 状态" to legacyTrack.state.toString(),
                            "设备" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                            "Android API" to android.os.Build.VERSION.SDK_INT.toString(),
                        ),
                    )
                }
                return legacyTrack
            } catch (legacyError: Exception) {
                try { legacyTrack?.release() } catch (_: Exception) {}
                legacyError.addSuppressed(pcm16Error)
                val details = "legacy ${describeAudioTrackFailure(legacyError)}; " +
                    "PCM_16BIT ${describeAudioTrackFailure(pcm16Failure)}; " +
                    "PCM_FLOAT ${describeAudioTrackFailure(floatFailure)}"
                throw IllegalStateException(
                    "系统配音音轨创建失败：$details".take(480),
                    legacyError,
                )
            }
        }
    }

    /** Publish a coherent line/position snapshot from the player ticker. */
    fun updateTimelinePosition(lineIndex: Int, positionMs: Long) {
        latestTimelineCursor = DubbingTimelineCursor(lineIndex, positionMs.coerceAtLeast(0L))
    }

    /** Pre-mount ready PCM before its subtitle start; Media3 PTS drives the eventual playback. */
    fun primeUpcomingCues(positionMs: Long, lookAheadMs: Long = 2_500L) {
        if (released || usesImmediateTimelinePlayback || lines.isEmpty()) return
        val position = positionMs.coerceAtLeast(0L)
        val windowMs = lookAheadMs.coerceAtLeast(0L)
        val horizon = if (Long.MAX_VALUE - position < windowMs) Long.MAX_VALUE else position + windowMs
        val busGeneration = outputBus.generationToken()
        synchronized(prefedCueKeys) {
            if (prefeedGeneration != busGeneration) {
                prefedCueKeys.clear()
                prefeedCompletions.keys.removeIf { !it.startsWith("$busGeneration:") }
                prefeedGeneration = busGeneration
            }
        }
        val candidates = lines.indices.asSequence()
            .map(::resolveChunkIndex)
            .distinct()
            .filter { index ->
                val cue = lines.getOrNull(index) ?: return@filter false
                DubbingTimeline.isPlayableCue(cue) && !cue.zh.isNullOrBlank() &&
                    cue.endMs > position && cue.startMs <= horizon
            }
            .sortedWith(compareBy<Int> { lines[it].startMs }.thenBy { it })
            .take(MAX_PREFEED_CUES)
            .toList()

        for (index in candidates) {
            if (!isPrepared(index) || isSilent(index)) continue
            val key = "$busGeneration:$index"
            val completion = synchronized(prefedCueKeys) {
                if (!prefedCueKeys.add(key)) null
                else kotlinx.coroutines.CompletableDeferred<Boolean>().also { prefeedCompletions[key] = it }
            } ?: continue
            val cacheGenerationAtRequest = synchronized(cacheConfigurationLock) {
                cacheInitGeneration.get()
            }
            fun isPrefeedCurrent(): Boolean =
                !released &&
                    outputBus.generationToken() == busGeneration &&
                    isCacheConfigurationCurrent(cacheGenerationAtRequest)
            try {
                audioExec.execute {
                    var published = false
                    try {
                        if (!isPrefeedCurrent()) return@execute
                        val loaded = load(index) { isPrefeedCurrent() } ?: return@execute
                        val output = DubbingOutputPcmPolicy.normalize(loaded.first, loaded.second) {
                            if (!isPrefeedCurrent()) {
                                throw kotlinx.coroutines.CancellationException("Prefetched PCM is stale")
                            }
                        }
                        if (output.samples.isEmpty()) return@execute
                        if (lines.getOrNull(resolveChunkIndex(index)) == null) return@execute
                        if (!isPrefeedCurrent()) return@execute
                        published = publishCueToBus(
                            index = index,
                            samples = output.samples,
                            sampleRate = output.sampleRate,
                            busGeneration = busGeneration,
                        ) { isPrefeedCurrent() } >= 0L
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Seek, mode change or engine replacement invalidated the asynchronous task.
                    } catch (error: Exception) {
                        android.util.Log.d("DubbingEngine", "Prefeed failed for cue ${index + 1}", error)
                    } finally {
                        completion.complete(published)
                        if (!published) {
                            prefedCueKeys.remove(key)
                            prefeedCompletions.remove(key, completion)
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                completion.complete(false)
                prefedCueKeys.remove(key)
                prefeedCompletions.remove(key, completion)
            }
        }
    }

    /** Wait briefly for the first ready cue to be mounted before the initial player start. */
    suspend fun primeUpcomingCuesAndWait(
        positionMs: Long,
        lookAheadMs: Long = 2_500L,
        waitMs: Long = PREFEED_STARTUP_WAIT_MS,
    ) {
        if (released || usesImmediateTimelinePlayback || lines.isEmpty()) return
        primeUpcomingCues(positionMs, lookAheadMs)
        val position = positionMs.coerceAtLeast(0L)
        val windowMs = lookAheadMs.coerceAtLeast(0L)
        val horizon = if (Long.MAX_VALUE - position < windowMs) Long.MAX_VALUE else position + windowMs
        val busGeneration = outputBus.generationToken()
        val firstIndex = lines.indices.asSequence()
            .map(::resolveChunkIndex)
            .distinct()
            .filter { index ->
                val cue = lines.getOrNull(index) ?: return@filter false
                DubbingTimeline.isPlayableCue(cue) && !cue.zh.isNullOrBlank() &&
                    cue.endMs > position && cue.startMs <= horizon &&
                    isPrepared(index) && !isSilent(index)
            }
            .minWithOrNull(compareBy<Int> { lines[it].startMs }.thenBy { it }) ?: return
        val completion = prefeedCompletions["$busGeneration:$firstIndex"] ?: return
        kotlinx.coroutines.withTimeoutOrNull(waitMs.coerceAtLeast(0L)) { completion.await() }
    }

    private fun mixCueKey(index: Int): String = "$cacheWriterId:${resolveChunkIndex(index)}"

    /**
     * 把一句配音 PCM 挂到共享混流总线，返回它实际开口的媒体时间（微秒）；被拒绝返回 -1。
     *
     * - 期望开口时间 = 字幕开始 − 固定提前量（与字幕选句同一个提前量），
     *   否则字幕提前了而配音还停在原始时间，听起来永远慢半拍。
     * - 上一句没说完时由总线把这一句排在后面：绝不叠音，视频也不暂停。
     * - 排队后如果会拖到窗口末尾之后，再额外压缩一点把时间债还掉（缓存里的音频不变），
     *   避免延迟越积越多。
     * - 准备晚了的句子从当前播放头之后整句播出，而不是从中间切入丢掉开头。
     */
    private fun publishCueToBus(
        index: Int,
        samples: FloatArray,
        sampleRate: Int,
        busGeneration: Long,
        isStillCurrent: () -> Boolean,
    ): Long {
        val playIndex = resolveChunkIndex(index)
        val cue = lines.getOrNull(playIndex) ?: return -1L
        val leadMs = SubtitleTimingOffset.LEAD_MS
        val desiredUs = (cue.startMs - leadMs).coerceAtLeast(0L) * 1_000L
        // latestTimelineCursor 是 tick 的时间轴位置（已包含提前量），还原成真实播放头。
        val playheadUs = (latestTimelineCursor.positionMs - leadMs).coerceAtLeast(0L) * 1_000L
        val key = mixCueKey(index)
        val durationUs = samples.size * 1_000_000L / sampleRate.coerceAtLeast(1)

        var output = samples
        val projectedStartUs = outputBus.projectStartUs(key, desiredUs, playheadUs, durationUs)
        val windowEndMs = chunkWindowEndByFirst[playIndex] ?: cue.endMs
        val endLimitUs = (windowEndMs - leadMs + DubbingFitPolicy.CATCH_UP_TOLERANCE_MS)
            .coerceAtLeast(0L) * 1_000L
        val catchUpRatio = DubbingFitPolicy.catchUpRatio(projectedStartUs, durationUs, endLimitUs)
        if (catchUpRatio < 1f) {
            output = try {
                Wsola.stretch(samples, catchUpRatio)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 追赶压缩只是优化；失败就按原速排队播放，绝不因此丢句。
                android.util.Log.w("DubbingEngine", "Catch-up compression failed for line ${index + 1}", error)
                samples
            }
        }

        val startedAtUs = outputBus.feedSequenced(
            cueKey = key,
            desiredMediaTimeUs = desiredUs,
            notBeforeUs = playheadUs,
            samples = output,
            sourceSampleRate = sampleRate,
            expectedGeneration = busGeneration,
            isStillCurrent = isStillCurrent,
        )
        val queuedDelayUs = startedAtUs - desiredUs
        if (startedAtUs >= 0L && queuedDelayUs > 150_000L &&
            firstQueuedDelayReported.compareAndSet(false, true)
        ) {
            logDubbingInfo(
                "配音句子被前一句顶后开口（已排队，不叠音、不暂停视频）",
                mapOf(
                    "字幕行号" to "${playIndex + 1}/${lines.size}",
                    "字幕开始时间 ms" to cue.startMs.toString(),
                    "期望开口时间 ms" to (desiredUs / 1_000L).toString(),
                    "实际开口时间 ms" to (startedAtUs / 1_000L).toString(),
                    "排队延迟 ms" to (queuedDelayUs / 1_000L).toString(),
                    "本句音频时长 ms" to (durationUs / 1_000L).toString(),
                    "追赶压缩比例" to catchUpRatio.toString(),
                    "字幕/配音提前量 ms" to leadMs.toString(),
                    "当前视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                ),
            )
        }
        return startedAtUs
    }

    /**
     * 清掉本引擎挂在共享总线上的所有句子，并允许它们按新内容重新预挂载。
     * 总线属于播放器、由所有引擎共用：引擎被替换或重新合成后，如果旧音频还留在总线上，
     * 到点就会和新配音叠在一起一起播出。
     */
    private fun purgeMountedCues() {
        outputBus.removeCuesWithPrefix("$cacheWriterId:")
        synchronized(prefedCueKeys) {
            prefedCueKeys.clear()
            prefeedCompletions.clear()
        }
    }

    /**
     * 开播前调用：撤掉总线上已有的句子，让起播位置附近的句子从头按期望时间重新挂载。
     * 之前（例如播放器暂停预读期间）挂上去的句子，是按当时被预读推远的读头排的版，
     * 位置已经偏后，而挂载去重（hasCue/prefed）会让它们永远不再重排。
     */
    fun remountForPlaybackStart(timelinePositionMs: Long) {
        if (released || usesImmediateTimelinePlayback) return
        purgeMountedCues()
        // 挂载时的"不早于播放头"以这个游标为准；起播前游标可能还停在上一次播放的位置。
        updateTimelinePosition(lineAt(timelinePositionMs), timelinePositionMs)
    }

    /**
     * Keep a live System TTS utterance or an overlong PCM tail alive across normal subtitle
     * boundaries. The next tick will start the newest still-current cue after this sentence ends.
     */
    fun shouldContinueCurrentCue(nextLineIndex: Int): Boolean {
        // Generated PCM is timestamp-scheduled. Only experimental live System TTS needs serialization.
        if ((tts as? TimelineTtsPlayback)?.useDirectTimelinePlayback != true) return false
        if (released) return false
        val currentIndex = _currentLine.value
        if (currentIndex < 0 || currentIndex == nextLineIndex) return false

        val directTtsActive =
            (tts as? TimelineTtsPlayback)?.useDirectTimelinePlayback == true &&
                activeTimelineUtteranceId != null

        var audioFinished = false
        var pcmOutputActive = false
        val activeTrack = track
        if (playbackStartedLine == currentIndex && activeTrack != null) {
            val finalChunkSamples = activeTrackFinalChunkSampleCount
            val playedSamples = activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            val trackStopped = activeTrack.playState == AudioTrack.PLAYSTATE_STOPPED

            if (finalChunkSamples > 0) {
                // This is the final chunk: wait for its real playback head/stop signal before
                // accepting the next cue, even when the cue was not classified as an overrun.
                audioFinished = trackStopped || playedSamples >= finalChunkSamples.toLong()
                pcmOutputActive = !audioFinished
            } else {
                // A non-final static chunk can briefly stop while the next chunk is prepared.
                // Treat that transition as part of this sentence instead of starting the next one
                // and cutting the current voice at a subtitle boundary.
                pcmOutputActive = activeCueOverrunsWindow ||
                    activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING
            }
        }

        if (activeCueOverrunsWindow && audioFinished) {
            synchronized(playbackControlLock) {
                val latestTrack = track
                val latestFinalChunkSamples = activeTrackFinalChunkSampleCount
                val finalChunkReached = playbackStartedLine == currentIndex && latestTrack != null &&
                    latestFinalChunkSamples > 0 &&
                    (latestTrack.playbackHeadPosition.toLong() and 0xffff_ffffL) >= latestFinalChunkSamples.toLong()
                val finalChunkStopped = playbackStartedLine == currentIndex && latestTrack != null &&
                    latestFinalChunkSamples > 0 && latestTrack.playState == AudioTrack.PLAYSTATE_STOPPED
                if (track == null || finalChunkReached || finalChunkStopped) {
                    activeCueOverrunsWindow = false
                    activeTrackFinalChunkSampleCount = -1
                }
            }
        }

        val shouldKeepCurrentCue = DubbingOverrunPolicy.shouldHold(
            currentLineIndex = currentIndex,
            nextLineIndex = nextLineIndex,
            liveUtteranceActive = directTtsActive,
            pcmOutputActive = pcmOutputActive,
            currentAudioFinished = audioFinished,
        )
        if (shouldKeepCurrentCue) {
            // The duration estimate or initial timing plan may be optimistic (especially for OEM
            // System TTS). If voice actually reaches the next cue boundary still active, promote
            // it to a synchronization hold so later captions are not skipped while this sentence
            // finishes. PlaybackService observes this flag on the same 50 ms timeline tick.
            synchronized(playbackControlLock) {
                if (!released && _currentLine.value == currentIndex) {
                    activeCueOverrunsWindow = true
                }
            }
        }
        return shouldKeepCurrentCue
    }

    /** 播放某一行（先停上一行）。
     *  AudioTrack 创建/写入/播放（含文件 load IO）全部在专用线程，不阻塞调用方（主线程 tick）。 */
    fun playAt(
        idx: Int,
        volume: Float = 1.0f,
        positionMs: Long? = null,
        playerSpeed: Float = 1.0f,
        playbackAllowed: (() -> Boolean)? = null,
        completeCueWhenLate: Boolean = false,
        catchUpSyncRate: Float = 1.0f,
    ) {
        if (released) return
        val line = lines.getOrNull(idx) ?: return
        if (!DubbingTimeline.isPlayableCue(line)) return
        // PCM cues follow Media3's media clock; reject in-flight feed after a seek or mode change.
        val busGenerationAtRequest = outputBus.generationToken()
        val cacheGenerationAtRequest = synchronized(cacheConfigurationLock) {
            cacheInitGeneration.get()
        }
        stop()
        activeVolume = volume.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f
        // Seed the asynchronous startup with the current ExoPlayer speed; ticker updates can
        // override it while cache loading / AudioTrack creation is still in progress.
        activePlayerSpeed = DubbingTiming.normalizePlayerSpeed(playerSpeed)
        val generation = synchronized(playbackControlLock) {
            activePlaybackGuard = playbackAllowed
            playbackStartedLine = -1
            lastFedLine = -1
            _currentLine.value = idx
            playbackGeneration.incrementAndGet()
        }

        val timelineTts = (tts as? TimelineTtsPlayback)?.takeIf { it.useDirectTimelinePlayback }
        if (timelineTts != null) {
            val spokenText = line.zh?.trim().orEmpty()
            if (spokenText.isBlank()) {
                markCuePrepared(idx, hasAudio = false)
                synchronized(playbackControlLock) {
                    if (playbackGeneration.get() == generation && _currentLine.value == idx) {
                        playbackStartedLine = -1
                        _currentLine.value = -1
                    }
                }
                return
            }
            val cueDurationMs = (line.endMs - line.startMs).coerceAtLeast(1L)
            val spokenCodePointCount = DubbingSyncHoldPolicy.spokenCodePointCount(spokenText)
            val punctuationPauseMs = DubbingSyncHoldPolicy.punctuationPauseMs(spokenText)
            val estimatedMsPerCodePoint = directTtsMsPerCodePoint
            val estimatedSpeechMs = DubbingSyncHoldPolicy.estimateSpeechDurationMs(
                text = spokenText,
                msPerCodePoint = estimatedMsPerCodePoint,
            ).coerceAtLeast(1L)
            val baseSpeechRate = io.translatetube.local.tts.TtsSpeechRatePolicy.normalize(speed)
            val timelineSpeed = DubbingTiming.normalizePlayerSpeed(playerSpeed)
            val estimatedAtEffectiveRateMs =
                (estimatedSpeechMs.toDouble() / (baseSpeechRate * timelineSpeed).toDouble())
                    .toLong().coerceAtLeast(1L)
            val predictedOverrun = DubbingSyncHoldPolicy.wouldOverrunCueWindow(
                estimatedSpeechMs = estimatedAtEffectiveRateMs,
                cueDurationMs = cueDurationMs,
                baseSpeechRate = 1.0f,
            )
            // Both genuinely long cues and queued late cues may use the bounded recovery cap.
            // Normal on-time System TTS stays on the lower cap to avoid rushing every sentence.
            val syncRateLimit = if (predictedOverrun || completeCueWhenLate) {
                DubbingTiming.MAX_CATCH_UP_SYNC_RATE
            } else {
                DubbingTiming.MAX_SYNC_RATE
            }
            val timingRate = DubbingSyncHoldPolicy.timelineRate(
                estimatedSpeechMs = estimatedAtEffectiveRateMs,
                cueDurationMs = cueDurationMs,
                maxSyncRate = syncRateLimit,
                targetRate = if (completeCueWhenLate) catchUpSyncRate else 1.0f,
            )
            if (predictedOverrun) {
                android.util.Log.w(
                    "DubbingEngine",
                    "系统 TTS 长句预计超出时间槽，将以自适应语速完整朗读并顺序补播后续字幕：line=${idx + 1}, " +
                        "estimatedAtEffectiveRateMs=$estimatedAtEffectiveRateMs, windowMs=$cueDurationMs, " +
                        "maxRate=$syncRateLimit",
                )
                runCatching {
                    io.translatetube.local.util.AppLogStore.warning(
                        appContext,
                        "DubbingTimeline",
                        "系统 TTS 第 ${idx + 1} 句预计超出时间槽，将完整朗读且不暂停视频；期间经过的字幕进入顺序补播",
                        diagnosticContext = mapOf(
                            "字幕行号" to "${idx + 1}/${lines.size}",
                            "配音引擎" to "系统TTS（即时模式）",
                            "字幕开始时间 ms" to line.startMs.toString(),
                            "字幕截止时间 ms" to line.endMs.toString(),
                            "当前视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                            "原始估算朗读时长 ms" to estimatedSpeechMs.toString(),
                            "每字符时长估算 ms" to estimatedMsPerCodePoint.toString(),
                            "应用当前语速后估算 ms" to estimatedAtEffectiveRateMs.toString(),
                            "可用时间槽 ms" to cueDurationMs.toString(),
                            "同步倍率" to timingRate.toString(),
                            "用户语速" to baseSpeechRate.toString(),
                            "视频播放速度" to timelineSpeed.toString(),
                            "处理方式" to "不暂停视频；维持本句完整输出，并按时间顺序补播等待中的字幕",
                        ),
                    )
                }
            }
            val speechRate = io.translatetube.local.tts.TtsSpeechRatePolicy.normalizeTimeline(
                baseSpeechRate * timingRate * timelineSpeed,
            )
            val utteranceId = "timeline-${System.nanoTime()}-$idx-$generation"
            val directTtsStartedAtNanos = AtomicLong(0L)
            val ownsCue = synchronized(playbackControlLock) {
                if (
                    !released &&
                    playbackGeneration.get() == generation &&
                    _currentLine.value == idx &&
                    isActivePlaybackAllowedLocked()
                ) {
                    activeTimelineUtteranceId = utteranceId
                    activeCueOverrunsWindow = predictedOverrun
                    activeTrackFinalChunkSampleCount = -1
                    true
                } else false
            }
            if (!ownsCue) return
            // Serialize TTS submission with stop(): otherwise disable can run after the
            // generation check but just before speakOnTimeline(), allowing a stale utterance
            // to begin after stopTimelineSpeech() has already returned.
            val accepted = synchronized(playbackControlLock) {
                if (
                    released ||
                    playbackGeneration.get() != generation ||
                    _currentLine.value != idx ||
                    activeTimelineUtteranceId != utteranceId ||
                    !isActivePlaybackAllowedLocked()
                ) {
                    false
                } else {
                    timelineTts.speakOnTimeline(
                        text = spokenText,
                        speed = speechRate,
                        speakerId = line.speakerId,
                        volume = activeVolume,
                        utteranceId = utteranceId,
                        onStarted = {
                            val stillCurrent = synchronized(playbackControlLock) {
                                val allowed = !released &&
                                    playbackGeneration.get() == generation &&
                                    _currentLine.value == idx &&
                                    isActivePlaybackAllowedLocked()
                                if (allowed) {
                                    playbackStartedLine = idx
                                    directTtsStartedAtNanos.set(System.nanoTime())
                                }
                                allowed
                            }
                            if (stillCurrent) {
                                if (firstPlaybackReported.compareAndSet(false, true)) {
                                    logDubbingInfo(
                                        "首句系统 TTS 已触发实际朗读回调",
                                        mapOf(
                                            "字幕行号" to "${idx + 1}/${lines.size}",
                                            "配音引擎名称" to (tts?.name ?: "未知引擎"),
                                            "字幕开始时间 ms" to line.startMs.toString(),
                                            "字幕结束时间 ms" to line.endMs.toString(),
                                            "当前视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                                            "中文译文字符数" to spokenText.length.toString(),
                                            "人物/音色编号" to line.speakerId.toString(),
                                            "实际 TTS 语速" to speechRate.toString(),
                                            "播放器速度" to timelineSpeed.toString(),
                                            "输出音量" to activeVolume.toString(),
                                            "AudioManager 音乐是否活跃" to runCatching {
                                                (appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                                                    ?.isMusicActive?.toString() ?: "不可用"
                                            }.getOrDefault("读取失败"),
                                        ),
                                    )
                                }
                                runCatching { onPlaybackStarted?.invoke(idx, generation) }
                                    .onFailure {
                                        android.util.Log.w(
                                            "DubbingEngine",
                                            "Could not publish live system-TTS playback state",
                                            it,
                                        )
                                    }
                            } else {
                                timelineTts.stopTimelineSpeech(utteranceId)
                            }
                        },
                        onCompleted = { error ->
                            val completedAtNanos = System.nanoTime()
                            val startedAtNanos = directTtsStartedAtNanos.getAndSet(0L)
                            val stillCurrent = synchronized(playbackControlLock) {
                                if (activeTimelineUtteranceId == utteranceId) {
                                    activeTimelineUtteranceId = null
                                    activeCueOverrunsWindow = false
                                }
                                !released &&
                                    playbackGeneration.get() == generation &&
                                    _currentLine.value == idx
                            }
                            if (stillCurrent && error.isNullOrBlank() && startedAtNanos > 0L) {
                                val elapsedMs = ((completedAtNanos - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)
                                if (firstPlaybackCompletedReported.compareAndSet(false, true)) {
                                    logDubbingInfo(
                                        "首句系统 TTS 朗读完成",
                                        mapOf(
                                            "字幕行号" to "${idx + 1}/${lines.size}",
                                            "实际朗读耗时 ms" to elapsedMs.toString(),
                                            "中文译文码点数" to spokenCodePointCount.toString(),
                                            "实际 TTS 语速" to speechRate.toString(),
                                            "播放器速度" to timelineSpeed.toString(),
                                        ),
                                    )
                                }
                                val oldEstimate = directTtsMsPerCodePoint
                                val newEstimate = DubbingSyncHoldPolicy.updateSpeechDurationEstimate(
                                    currentMsPerCodePoint = oldEstimate,
                                    elapsedMs = elapsedMs,
                                    codePointCount = spokenCodePointCount,
                                    appliedSpeechRate = speechRate,
                                    estimatedPunctuationPauseMs = punctuationPauseMs,
                                )
                                directTtsMsPerCodePoint = newEstimate
                                android.util.Log.d(
                                    "DubbingEngine",
                                    "System TTS timing calibration: line=${idx + 1}/${lines.size} " +
                                        "speechChars=$spokenCodePointCount punctuationPauseMs=$punctuationPauseMs " +
                                        "elapsedMs=$elapsedMs appliedRate=$speechRate " +
                                        "msPerCodePoint=$oldEstimate->$newEstimate",
                                )
                            }
                            if (!error.isNullOrBlank()) {
                                if (stillCurrent) {
                                    markCuePrepared(idx, hasAudio = false)
                                    synchronized(playbackControlLock) {
                                        if (playbackGeneration.get() == generation && _currentLine.value == idx) {
                                            playbackStartedLine = -1
                                            _currentLine.value = -1
                                        }
                                    }
                                    reportSynthesisError("第 ${idx + 1} 行系统 TTS 朗读失败：$error")
                                }
                            }
                        },
                    )
                }
            }
            if (!accepted) {
                markCuePrepared(idx, hasAudio = false)
                synchronized(playbackControlLock) {
                    if (activeTimelineUtteranceId == utteranceId) {
                        activeTimelineUtteranceId = null
                        activeCueOverrunsWindow = false
                    }
                    if (playbackGeneration.get() == generation && _currentLine.value == idx) {
                        playbackStartedLine = -1
                        _currentLine.value = -1
                    }
                }
            }
            return
        }

        try {
            audioExec.execute {
                try {
                    // An in-flight PCM read must not outlive the TTS configuration that requested it.
                    if (!isCacheConfigurationCurrent(cacheGenerationAtRequest)) {
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    // 混流总线架构：配音 PCM 挂到块首媒体时间，由 ExoPlayer 音频渲染
                    // 管线里的 DubbingAudioProcessor 与原声逐样本相加——与视频同一播放
                    // 时钟（seek/暂停/倍速天然跟随），句间停顿与超窗截断由总线时间窗
                    // 自然完成，绝不排队等上一句读完。
                    val pcmLine = lines.getOrNull(resolveChunkIndex(idx)) ?: line
                    // 预挂载已经把这一句排进总线：不重新读盘、不重新压缩、不重新排队，
                    // 否则每次 tick/恢复/换行都会把已排好的时间线再扰动一遍。
                    if (outputBus.hasCue(mixCueKey(idx))) {
                        val alreadyMounted = synchronized(playbackControlLock) {
                            val current = !released && playbackGeneration.get() == generation &&
                                _currentLine.value == idx && isActivePlaybackAllowedLocked() &&
                                isCacheConfigurationCurrent(cacheGenerationAtRequest) &&
                                outputBus.generationToken() == busGenerationAtRequest
                            if (current) {
                                lastFedLine = idx
                                playbackStartedLine = idx
                            }
                            current
                        }
                        if (alreadyMounted) {
                            runCatching { onPlaybackStarted?.invoke(idx, generation) }
                                .onFailure {
                                    android.util.Log.w("DubbingEngine", "Could not publish dubbing playback state", it)
                                }
                        }
                        return@execute
                    }
                    val loaded = load(idx) {
                        !released && playbackGeneration.get() == generation &&
                            isCacheConfigurationCurrent(cacheGenerationAtRequest)
                    } ?: run {
                        // A missing/corrupt cache is queued for re-synthesis by load().
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    if (released || playbackGeneration.get() != generation ||
                        !isCacheConfigurationCurrent(cacheGenerationAtRequest)
                    ) {
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    val outputPcm = DubbingOutputPcmPolicy.normalize(loaded.first, loaded.second) {
                        if (released || playbackGeneration.get() != generation ||
                            !isCacheConfigurationCurrent(cacheGenerationAtRequest)
                        ) {
                            throw kotlinx.coroutines.CancellationException("配音输出准备已过期")
                        }
                    }
                    if (outputPcm.samples.isEmpty()) {
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    val canFeed = synchronized(playbackControlLock) {
                        !released && playbackGeneration.get() == generation &&
                            _currentLine.value == idx && isActivePlaybackAllowedLocked() &&
                            isCacheConfigurationCurrent(cacheGenerationAtRequest)
                    }
                    if (!canFeed) return@execute
                    val acceptedFeed = publishCueToBus(
                        index = idx,
                        samples = outputPcm.samples,
                        sampleRate = outputPcm.sampleRate,
                        busGeneration = busGenerationAtRequest,
                    ) {
                        !released && playbackGeneration.get() == generation &&
                            _currentLine.value == idx &&
                            isCacheConfigurationCurrent(cacheGenerationAtRequest)
                    } >= 0L
                    if (!acceptedFeed) {
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    val ownsCue = synchronized(playbackControlLock) {
                        val current = !released && playbackGeneration.get() == generation &&
                            _currentLine.value == idx && isActivePlaybackAllowedLocked() &&
                            isCacheConfigurationCurrent(cacheGenerationAtRequest) &&
                            outputBus.generationToken() == busGenerationAtRequest
                        if (current) {
                            lastFedLine = idx
                            playbackStartedLine = idx
                        }
                        current
                    }
                    if (!ownsCue) return@execute
                    if (firstPlaybackReported.compareAndSet(false, true)) {
                        logDubbingInfo(
                            "首条中文配音 PCM 已挂载混流总线",
                            mapOf(
                                "字幕行号" to "${idx + 1}/${lines.size}",
                                "配音引擎名称" to (tts?.name ?: "未知引擎"),
                                "字幕音频起始时间 ms" to pcmLine.startMs.toString(),
                                "视频当前位置 ms" to latestTimelineCursor.positionMs.toString(),
                                "归一化 PCM 样本数" to outputPcm.samples.size.toString(),
                                "归一化 PCM 采样率 Hz" to outputPcm.sampleRate.toString(),
                                "估算音频时长 ms" to (outputPcm.samples.size.toDouble() * 1000.0 /
                                    outputPcm.sampleRate.coerceAtLeast(1)).toLong().toString(),
                                "输出音量" to activeVolume.toString(),
                                "播放器速度" to activePlayerSpeed.toString(),
                                "混流总线代次" to outputBus.generationToken().toString(),
                                "输出总线接受数据" to acceptedFeed.toString(),
                            ),
                        )
                    }
                    runCatching { onPlaybackStarted?.invoke(idx, generation) }
                        .onFailure {
                            android.util.Log.w("DubbingEngine", "Could not publish dubbing playback state", it)
                        }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w(
                        "DubbingEngine",
                        "Dubbing mix feed failed for line ${idx + 1}",
                        error,
                    )
                    logDubbingError(
                        "第 ${idx + 1}/${lines.size} 句配音 PCM 挂载混流总线失败",
                        error,
                        mapOf(
                            "配音引擎名称" to (tts?.name ?: "未知引擎"),
                            "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                            "字幕行号" to "${idx + 1}/${lines.size}",
                            "字幕时间轴 ms" to "${line.startMs}–${line.endMs}",
                            "播放请求代次" to generation.toString(),
                            "当前播放代次" to playbackGeneration.get().toString(),
                            "混流总线请求代次" to busGenerationAtRequest.toString(),
                            "当前混流总线代次" to outputBus.generationToken().toString(),
                            "当前视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                            "当前活动字幕行号" to (_currentLine.value + 1).toString(),
                            "AudioManager 音乐是否活跃" to runCatching {
                                (appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                                    ?.isMusicActive?.toString() ?: "不可用"
                            }.getOrDefault("读取失败"),
                        ),
                    )
                    runCatching { markCuePrepared(idx, hasAudio = false) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            _currentLine.value = -1
        }
    }

    /** Stop voice output promptly on pause; resume will rebuild the cue at the current video time. */
    fun pausePlayback() {
        if (released) return
        // Invalidate an in-flight PCM load/track creation and clear "voice is audible" immediately.
        // PlaybackService reselects the active cue and calls playAt() when ExoPlayer resumes, so
        // retaining a static AudioTrack here only holds native buffer memory while the user pauses.
        val pausedLine = _currentLine.value
        val playbackWasStarted = pausedLine >= 0 && playbackStartedLine == pausedLine
        val engineNameAtPause = tts?.name ?: "未知引擎"
        val invalidated = invalidatePlayback()
        logDubbingInfo(
            "配音输出已暂停",
            mapOf(
                "暂停前字幕行号" to if (pausedLine >= 0) "${pausedLine + 1}/${lines.size}" else "当前没有活动字幕行",
                "暂停前是否已进入实际输出" to playbackWasStarted.toString(),
                "配音引擎" to engineNameAtPause,
                "输出模式" to if (usesImmediateTimelinePlayback) "系统 TTS 即时朗读" else "PCM 混流",
                "作废播放代次" to invalidated.first.toString(),
                "当前视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                "混流总线代次" to outputBus.generationToken().toString(),
            ),
        )
        // Stop only the exact utterance owned by the invalidated generation. A late pause from
        // an older cue must never stop a newer system-TTS utterance.
        val directTts = (tts as? TimelineTtsPlayback)?.takeIf { it.useDirectTimelinePlayback }
        invalidated.second?.let { staleId -> directTts?.stopTimelineSpeech(staleId) }
        // 混流架构：总线上的 PCM 保留挂载（媒体时间窗不变），ExoPlayer 暂停时音频
        // 渲染管线同步静默；恢复后 invalidatePlayback 已重置 lastFedLine，tick 重新
        // playAt() 会把当前时刻的块重新挂上，无需任何音轨重建。
    }

    /** 混流架构下恢复：清当前行让下一次 tick 按媒体时间重新挂载正确的块。 */
    fun resumePlayback() {
        if (released) return
        _currentLine.value = -1
        logDubbingInfo(
            "配音输出已恢复，等待按视频时间轴重新选择字幕",
            mapOf(
                "配音引擎" to (tts?.name ?: "未知引擎"),
                "当前时间轴字幕行号" to lineAt(latestTimelineCursor.positionMs).let { lineIndex ->
                    if (lineIndex >= 0) "${lineIndex + 1}/${lines.size}" else "当前为字幕间隙"
                },
                "当前视频位置 ms" to latestTimelineCursor.positionMs.toString(),
                "已准备行数" to _preparedCount.value.toString(),
                "待合成队列行数" to pending.totalSize().toString(),
                "混流总线代次" to outputBus.generationToken().toString(),
            ),
        )
    }

    fun stop() {
        val invalidated = invalidatePlayback()
        invalidated.second?.let { staleId ->
            (tts as? TimelineTtsPlayback)?.stopTimelineSpeech(staleId)
        }
        // 混流架构：不清理总线——换行/间隙时旧块的媒体时间窗已过，读取侧自然无声。
    }

    /**
     * 这一行如果属于"相邻两句合并成一块配音"的块，并且整块配音已经就绪，返回整块实际念的文本；
     * 否则返回 null（这一行念的就是它自己的译文）。供字幕条与配音保持一致。
     */
    fun spokenCaptionFor(lineIdx: Int): String? {
        if (released || usesImmediateTimelinePlayback) return null
        val first = resolveChunkIndex(lineIdx)
        val merged = chunkTextByFirst[first]?.takeIf { it.isNotBlank() } ?: return null
        val isMergedChunk = chunkRedirect.containsValue(first)
        if (!isMergedChunk || !isPrepared(first) || isSilent(first)) return null
        // 合并文本里空译文行会退回原文，只有两行都有可用译文时才让字幕条跟随。
        val partners = chunkRedirect.filterValues { it == first }.keys
        if (lines.getOrNull(first)?.zh.isNullOrBlank() || partners.any { lines.getOrNull(it)?.zh.isNullOrBlank() }) return null
        return merged
    }

    /** 当前时间对应行下标（二分索引支持重叠字幕）；-1=间隙 */
    fun lineAt(ms: Long): Int = timeline.lineAt(ms)

    fun line(idx: Int): SubLine? = lines.getOrNull(idx)
    fun allLines(): List<SubLine> = lines

    /**
     * Changing synthesis speed changes the PCM cache identity. Cancel any in-flight synthesis,
     * invalidate readiness from the previous configuration, then rebuild the queue after the
     * blocking provider call has unwound. Existing matching disk-cache rows are still reusable.
     */
    fun setSpeed(s: Float) {
        val next = io.translatetube.local.tts.TtsSpeechRatePolicy.normalize(s)
        if (next == speed || released) return
        restartSynthesisForCurrentConfiguration(next)
    }

    private fun restartSynthesisForCurrentConfiguration(nextSpeed: Float? = null) {
        val generation = synchronized(cacheConfigurationLock) {
            if (released || (nextSpeed != null && nextSpeed == speed)) return
            // Publish the new rate and generation atomically so readers cannot pair the new
            // rate with the prior generation during rapid successive changes. A no-argument call
            // forces a fresh pass after the active TTS implementation itself has changed.
            if (nextSpeed != null) speed = nextSpeed
            cacheInitGeneration.incrementAndGet()
        }
        val oldCacheInit = cacheInitJob
        val oldSynthesis = synchronized(synthStartLock) { synthJob }
        oldCacheInit?.cancel()
        oldSynthesis?.cancel()
        // 语速/配置变了：总线上按旧配置合成的句子已经过期，撤掉后按新缓存重新预挂载。
        purgeMountedCues()
        synchronized(mem) { mem.clear() }
        resetReadinessForCurrentConfiguration()
        synchronized(pending) {
            pending.clear()
            priorityTarget = -1
            priorityLookAhead = 2
        }
        synthesisRealtimeFactorValue = Float.NaN
        synthesisRealtimeSampleCountValue = 0
        synthesisTimelineLagFactorValue = Float.NaN
        synthesisTimelineLagSampleCountValue = 0

        scope.launch {
            oldCacheInit?.join()
            oldSynthesis?.join()
            if (released || generation != cacheInitGeneration.get()) return@launch

            // Clear once more after joining: a validator already between two instructions may
            // have published progress immediately before observing cancellation.
            resetReadinessForCurrentConfiguration()
            synchronized(mem) { mem.clear() }
            synthesisErrorReported.set(false)
            firstSynthesisReported.set(false)
            val currentTts = tts
            if (currentTts is TimelineTtsPlayback && currentTts.useDirectTimelinePlayback) {
                lines.indices.forEach { index ->
                    val cue = lines[index]
                    markCuePrepared(
                        index,
                        hasAudio = DubbingTimeline.isPlayableCue(cue) && !cue.zh.isNullOrBlank(),
                    )
                }
                bumpPrepared()
                return@launch
            }
            synchronized(pending) {
                pending.clear()
                lines.indices
                    .filter { DubbingTimeline.isPlayableCue(lines[it]) }
                    .forEach { pending.offerLast(it) }
            }
            bumpPrepared()
            startSynthLoop()
        }
    }

    /**
     * Reset the whole cue ledger when a cache configuration changes. Non-playable rows are
     * terminally ready-without-audio, so they cannot stall the contiguous progress prefix.
     */
    private fun resetReadinessForCurrentConfiguration() {
        readinessProgress.clear(lines.size, ::publishReadinessProgress)
        lines.indices
            .filterNot { DubbingTimeline.isPlayableCue(lines[it]) }
            .forEach { markCuePrepared(it, hasAudio = false) }
    }

    /** Update the active voice volume immediately when the user toggles original audio. */
    fun updateVolume(volume: Float) {
        val normalized = volume.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f
        if (kotlin.math.abs(normalized - activeVolume) < 0.01f) return
        activeVolume = normalized
        // Live Android TTS takes its volume in the utterance Bundle; the next cue uses this
        // value. AudioTrack-backed engines can update the already-running track immediately.
        val currentTts = tts
        if (currentTts is TimelineTtsPlayback && currentTts.useDirectTimelinePlayback) return
        val active = track ?: return
        try {
            audioExec.execute {
                if (!released && track === active) {
                    runCatching { active.setVolume(normalized) }
                        .onFailure {
                            android.util.Log.w("DubbingEngine", "Could not update active voice volume", it)
                        }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Service is shutting down.
        }
    }

    /** Update the active voice without changing the selected TTS engine or its cached PCM. */
    fun updatePlayerSpeed(speed: Float) {
        val normalized = DubbingTiming.normalizePlayerSpeed(speed)
        if (kotlin.math.abs(normalized - activePlayerSpeed) < 0.01f) return
        activePlayerSpeed = normalized
        val rate = DubbingSyncHoldPolicy.playbackRate(
            syncRate = activeSyncRate,
            playerSpeed = normalized,
        )
        if (kotlin.math.abs(rate - activePlaybackRate) < 0.01f) return
        activePlaybackRate = rate
        val active = track ?: return
        try {
            audioExec.execute {
                if (!released && track === active) {
                    runCatching {
                        active.setPlaybackParams(PlaybackParams().setSpeed(rate).setPitch(1.0f))
                    }.onFailure {
                        android.util.Log.w("DubbingEngine", "Could not update active voice playback speed", it)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Service is shutting down.
        }
    }
    /**
     * Instant System TTS can speak directly without generated PCM. Background warm-up failures
     * must therefore never make an otherwise valid direct-speech cue appear unavailable.
     */
    private fun canSpeakDirectly(idx: Int): Boolean {
        if (!usesImmediateTimelinePlayback) return false
        val cue = lines.getOrNull(idx) ?: return false
        return DubbingTimeline.isPlayableCue(cue) && !cue.zh.isNullOrBlank()
    }

    /** 该行音频是否已可播放（纯内存判断，供 100ms tick 高频调用，绝不做磁盘 IO） */
    fun isPrepared(idx: Int): Boolean = canSpeakDirectly(idx) || readiness.isPrepared(idx)
    fun isSilent(idx: Int): Boolean = !canSpeakDirectly(idx) && readiness.isSilent(idx)

    /** Persist the exact cause when playback reaches a silent cue and restores the original track. */
    fun logSilentCueFallback(index: Int) {
        if (released || index !in lines.indices || !silentFallbackReported.add(index)) return
        val cue = lines[index]
        val rawCue = inputLinesForDiagnostics.getOrNull(index)
        val reason = silentCueReasonsByIndex[index] ?: when {
            !DubbingTimeline.isPlayableCue(cue) -> "字幕时间轴无效或原文字幕为空"
            cue.zh.isNullOrBlank() -> "中文译文为空或被译文质量规则过滤"
            !io.translatetube.local.tts.TtsTextPolicy.hasSpeakableContent(cue.zh.orEmpty()) ->
                "译文只有标点、符号或表情，没有可朗读内容"
            else -> "该句未获得可播放 PCM；没有记录到更具体的失败原因"
        }
        logDubbingInfo(
            "播放第 ${index + 1}/${lines.size} 句时未检测到可播放中文配音，已恢复原声",
            mapOf(
                "字幕行号" to "${index + 1}/${lines.size}",
                "原因" to reason,
                "故障阶段" to "字幕播放与原声回退",
                "配音引擎名称" to (tts?.name ?: "未知引擎"),
                "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                "配音引擎缓存/模型标识" to runCatching { tts?.cacheKey.orEmpty() }.getOrDefault("读取失败").take(1200),
                "原字幕内容预览" to rawCue?.text.orEmpty().take(240),
                "输入译文内容预览" to rawCue?.zh.orEmpty().take(240),
                "实际中文配音文本预览" to cue.zh.orEmpty().take(240),
                "字幕时间轴 ms" to "${rawCue?.startMs ?: cue.startMs}–${rawCue?.endMs ?: cue.endMs}",
                "源语言标记" to sourceLanguageForDiagnostics,
                "处理动作" to "该句不播放中文配音，保留视频原声，避免无声片段",
            ),
            tag = "DubbingFallback",
        )
    }

    /** Mark a sync-held sentence unavailable after a watchdog timeout so it cannot restart forever. */
    fun abortSyncHoldCue(idx: Int) {
        if (idx !in lines.indices) return
        markCuePrepared(idx, hasAudio = false)
        if (_currentLine.value == idx) stop()
    }

    /** 正在播放的行（-1=无） */
    fun currentLineIdx(): Int = _currentLine.value

    /** True only after this cue's AudioTrack actually started, not while PCM is merely ready. */
    fun hasStartedPlaybackFor(
        idx: Int,
        expectedPlaybackGeneration: Int? = null,
    ): Boolean =
        !released &&
            idx >= 0 &&
            _currentLine.value == idx &&
            playbackStartedLine == idx &&
            (expectedPlaybackGeneration == null ||
                playbackGeneration.get() == expectedPlaybackGeneration)

    fun release() {
        // Publish the released state under the same lock used to install cache files. Any write
        // that commits first is complete; any later write sees released and aborts.
        synchronized(cacheWriteLock) {
            if (released) return
            released = true
        }
        // 共享总线属于播放器：旧引擎挂上去的句子必须随引擎一起撤掉，
        // 否则新引擎的配音会和旧引擎残留的音频叠在一起播出。
        purgeMountedCues()
        logDubbingInfo(
            "配音引擎开始释放",
            mapOf(
                "配音引擎名称" to (tts?.name ?: "未知引擎"),
                "配音引擎实现类" to (tts?.javaClass?.name ?: "未知"),
                "字幕总行数" to lines.size.toString(),
                "已准备行数" to _preparedCount.value.toString(),
                "待合成队列行数" to pending.totalSize().toString(),
                "释放时活动字幕行号" to if (_currentLine.value >= 0) "${_currentLine.value + 1}/${lines.size}" else "没有活动字幕行",
                "输出模式" to if (usesImmediateTimelinePlayback) "系统 TTS 即时朗读" else "PCM 混流",
                "混流总线代次" to outputBus.generationToken().toString(),
            ),
        )
        // A retired engine must not retain every decoded PCM cue while its cancellation handlers
        // unwind. rememberMemoryAudio() also checks released under this same map lock, so an old
        // cache reader cannot repopulate the LRU after this clear.
        synchronized(mem) { mem.clear() }
        synchronized(playbackControlLock) {
            activePlaybackGuard = null
            playbackGeneration.incrementAndGet()
        }
        val oldTtsInstances = ttsInstances.toList()
        ttsInstances.clear()
        tts = null
        synthesisTts = null
        backgroundSynthesisRequested = false
        backgroundTtsInitializationJob?.cancel()
        backgroundTtsInitializationJob = null
        fallbackRequested.set(false)
        cacheInitGeneration.incrementAndGet()
        cacheInitJob?.cancel()
        // Serialize the task snapshot with startSynthLoop(). Without this lock, release()
        // could read a null/old Job while a new worker is being installed, then close the native
        // TTS engine underneath that just-started synthesis call.
        val activeSynthesis = synchronized(synthStartLock) { synthJob }
        activeSynthesis?.cancel()
        oldTtsInstances.filterIsInstance<TimelineTtsPlayback>()
            .forEach { runCatching { it.stopTimelineSpeech() } }
        val systemInstances = oldTtsInstances.filterIsInstance<io.translatetube.local.tts.SystemTts>()
        val nativeInstances = oldTtsInstances.filterNot { it is io.translatetube.local.tts.SystemTts }
        // Close latch-backed System TTS instances first so any waiting synth() call can unwind.
        systemInstances.forEach { engine -> runCatching { engine.close() } }
        when {
            nativeInstances.isEmpty() -> ttsCloseSignal.complete(Unit)
            activeSynthesis != null && !activeSynthesis.isCompleted -> {
                // Kokoro/Piper may be inside blocking native work. Defer native release until it unwinds.
                activeSynthesis.invokeOnCompletion {
                    try { nativeInstances.forEach { runCatching { it.close() } } }
                    finally { ttsCloseSignal.complete(Unit) }
                }
            }
            else -> {
                try { nativeInstances.forEach { runCatching { it.close() } } }
                finally { ttsCloseSignal.complete(Unit) }
            }
        }
        _currentLine.value = -1
        val old = track
        track = null
        // Queue a final barrier behind any in-flight track creation/write. Even when there is no
        // currently published track, an earlier playAt() may still be finishing on audioExec.
        try {
            audioExec.execute {
                try {
                    old?.pause()
                    old?.flush()
                    old?.release()
                } catch (e: Exception) {
                    android.util.Log.w("DubbingEngine", "AudioTrack cleanup failed during release", e)
                } finally {
                    audioCloseSignal.complete(Unit)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { old?.release() } catch (_: Exception) {}
            audioCloseSignal.complete(Unit)
        }
        // Existing queued tasks see released/generation and abort; the cleanup barrier remains queued.
        audioExec.shutdown()
    }

    private fun isStalePcmTemp(file: File, nowMs: Long): Boolean {
        if (!file.isFile || !file.name.endsWith(".pcm.tmp")) return false
        val modified = file.lastModified()
        return modified > 0L && nowMs >= modified && nowMs - modified > TEMP_PCM_AGE_MS
    }

    /** Release immediately, then await TTS and AudioTrack disposal before replacing this engine. */
    suspend fun releaseAndJoin() {
        release()
        // Cache validation runs on IO and may still be finishing a bounded file read after cancel.
        // Wait for it to exit before a replacement engine uses this video's cache directory.
        cacheInitJob?.join()
        // Closing SystemTts wakes its synthesis latch, but its coroutine still needs to unwind and
        // delete the temporary WAV. Join that task before constructing another engine/session.
        synthJob?.join()
        ttsCloseSignal.await()
        audioCloseSignal.await()
    }

    private companion object {
        // Bound opportunistic look-ahead work and keep the initial playback wait short.
        private const val MAX_PREFEED_CUES = 3
        private const val PREFEED_STARTUP_WAIT_MS = 180L
        private val cacheInstallLock = Any()
        private val nextCacheWriterId = AtomicInteger(0)
        private const val TEMP_PCM_AGE_MS = 6L * 60L * 60L * 1_000L
        // Keep each native static track well below Android's common 1 MiB static-buffer ceiling.
        // Keep static chunks small for Android 9/OEM AudioTrack implementations with
        // restrictive buffer limits. At 24 kHz this is at most one second of audio.
        private const val MAX_STATIC_TRACK_SAMPLES = 24_000
        private const val MIN_STATIC_CHUNK_BOUNDARY_TIMEOUT_MS = 5_000L
        private const val STATIC_CHUNK_BOUNDARY_TIMEOUT_GRACE_MS = 3_000L
        private const val STATIC_CHUNK_HEAD_STALL_TIMEOUT_MS = 5_000L
    }
}
