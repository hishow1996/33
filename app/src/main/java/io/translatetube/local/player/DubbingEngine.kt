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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 行级时间轴配音引擎：
 * Kokoro/Piper 等引擎在后台逐行合成并缓存 PCM，由 AudioTrack 按时间轴切行；
 * 系统 TTS 则在对应字幕时间点直接调用 Android 朗读服务，不再预合成 PCM。
 */
private class DubbingPcmLimitException(message: String) : IllegalStateException(message)
private class DubbingChunkBoundaryTimeoutException : IllegalStateException("等待配音分段结束超时")

private data class DubbingCacheInspection(
    val hasAudio: Boolean?,
    val stamp: DubbingCacheFileStamp?,
)

class DubbingEngine(
    ctx: Context,
    private val videoKey: String,
    private val scope: CoroutineScope,
) {
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
    private val ttsInstances = java.util.concurrent.CopyOnWriteArrayList<TtsEngine>()
    private val fallbackRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Return true when the service has scheduled a safe fallback-engine switch for this failed cue. */
    @Volatile var onFallbackRequested: ((index: Int, detail: String) -> Boolean)? = null
    @Volatile var onSynthesisError: ((String) -> Unit)? = null
    val currentTtsName: String get() = tts?.name.orEmpty()
    /** Diagnostic callback: the first successful PCM result for this session. */
    @Volatile var onFirstSynthesisReady: ((index: Int, sampleCount: Int, sampleRate: Int) -> Unit)? = null
    /** Diagnostic callback after AudioTrack.play() succeeds, not merely when synthesis is ready. */
    @Volatile var onPlaybackStarted: ((index: Int) -> Unit)? = null
    private val synthesisErrorReported = AtomicBoolean(false)
    private val firstSynthesisReported = AtomicBoolean(false)
    private var lines: List<SubLine> = emptyList()
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
    /** AudioTrack 操作专用单线程：write(MODE_STATIC) 数据拷贝耗时，绝不放主线程 */
    private val audioExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tt-audio").apply { priority = Thread.MAX_PRIORITY }
    }
    /** Serializes cache publication with release so a cancelled old run cannot install stale PCM. */
    private val cacheWriteLock = Any()
    private val cacheWriterId = nextCacheWriterId.incrementAndGet()
    private val ttsCloseSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    // Completes only after every queued AudioTrack operation and the final track cleanup have drained.
    private val audioCloseSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val readiness = DubbingReadiness()
    private val readinessProgress = DubbingReadinessProgress(readiness)
    private val pending = LinkedBlockingDeque<Int>()
    /** Cue currently inside the single synthesis worker, protected by the pending-queue lock. */
    @Volatile private var activeSynthesisIndex = -1
    /** Cache misses observed while the same cue is synthesizing are reconsidered when it exits. */
    private val retryAfterActiveSynthesis = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var priorityTarget = -1
    @Volatile private var synthJob: Job? = null
    @Volatile private var cacheInitJob: Job? = null
    private val synthStartLock = Any()
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

    private fun invalidatePlayback(): Int = synchronized(playbackControlLock) {
        playbackStartedLine = -1
        _currentLine.value = -1
        activePlaybackGuard = null
        playbackGeneration.incrementAndGet()
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

    fun attach(tts: TtsEngine, lines: List<SubLine>, sourceLanguage: String = "auto") {
        // Validate every incoming resource and timeline before publishing state. Even an over-limit
        // import must release the TTS instance it handed to this engine.
        val preparedState = try {
            require(lines.size <= DubbingTimeline.MAX_INPUT_CUE_COUNT) {
                "字幕行数超过配音安全上限，无法初始化配音"
            }
            val normalized = lines.map { line ->
                line.copy(zh = DubbingTranslationPolicy.usableText(line.text, line.zh, sourceLanguage))
            }
            normalized to DubbingTimeline(normalized)
        } catch (error: Exception) {
            runCatching { tts.close() }
            throw error
        }
        val preparedLines = preparedState.first
        val preparedTimeline = preparedState.second
        var ttsPublished = false
        try {
            // Publish the TTS instance, subtitle list and cache-init Job atomically with release().
            // Otherwise a concurrent retirement could miss this TTS instance or the newly launched job.
            synchronized(cacheWriteLock) {
                if (released) throw IllegalStateException("配音引擎已释放，不能重新绑定字幕")
                this.tts = tts
                ttsInstances.clear()
                ttsInstances.add(tts)
                ttsPublished = true
                fallbackRequested.set(false)
                this.lines = preparedLines
                this.timeline = preparedTimeline
            synthesisErrorReported.set(false)
            firstSynthesisReported.set(false)
            _totalLines.value = this.lines.size
            readinessProgress.clear(this.lines.size, ::publishReadinessProgress)
            pending.clear()
            priorityTarget = -1
            latestTimelineCursor = DubbingTimelineCursor()
            cacheInitJob?.cancel()
            val generation = cacheInitGeneration.incrementAndGet()
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

                    // Optional engines implementing TimelineTtsPlayback can speak directly on
                    // the timeline without creating PCM cache files. SystemTts deliberately does not
                    // implement this contract now: it uses the WAV/PCM path so timing, caching and
                    // AudioTrack compatibility stay consistent with Kokoro and Piper.
                    if (tts is TimelineTtsPlayback) {
                        for (i in this@DubbingEngine.lines.indices) {
                            checkCacheInitActive()
                            val cue = this@DubbingEngine.lines[i]
                            val hasSpeech = DubbingTimeline.isPlayableCue(cue) &&
                                !cue.zh.isNullOrBlank()
                            markCuePrepared(i, hasAudio = hasSpeech)
                        }
                        bumpPrepared()
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
                        val cacheInspection = inspectCache(file, ::checkCacheInitActive)
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
                        startSynthLoop()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("DubbingEngine", "PCM cache initialization failed", e)
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
            throw error
        }
    }


    /**
     * When playback reaches a line whose TTS is not ready, move that line and the next
     * two queued lines to the front. A seek must not wait for every earlier line to synthesize.
     */
    fun prioritizeAround(index: Int) {
        if (released || index !in lines.indices || index == priorityTarget) return
        synchronized(pending) {
            if (index == priorityTarget) return
            val queued = mutableListOf<Int>()
            pending.drainTo(queued)
            val reordered = DubbingQueueOrder.prioritize(queued, index, lines.size)
            reordered.forEach { pending.offerLast(it) }
            priorityTarget = index
        }
    }

    private fun startSynthLoop() {
        // Queue recovery and cache initialization can request startup concurrently. Serialize
        // the check-and-launch pair; otherwise both callers could observe an idle worker and
        // invoke the same native TTS engine concurrently.
        synchronized(synthStartLock) {
            // Never cancel an active provider call just to restart queue processing: many TTS
            // providers perform blocking native work and cancellation cannot stop it immediately.
            if (released || synthJob?.isActive == true) return
            synthJob = scope.launch(Dispatchers.Default) {
            // Poll under the same lock used by prioritizeAround(). Do not inspect isEmpty()
            // outside the lock: queue reordering temporarily drains the deque, and a separate
            // isEmpty() check could let this worker exit before the reordered work is restored.
            while (!released) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
                val workerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                try {
                val line = lines.getOrNull(idx) ?: continue
                if (!DubbingTimeline.isPlayableCue(line)) {
                    markCuePrepared(idx, hasAudio = false)
                    bumpPrepared()
                    continue
                }
                fun checkSynthesisActive() {
                    if (released || workerJob?.isActive == false) {
                        throw kotlinx.coroutines.CancellationException("配音缓存写入已取消")
                    }
                }
                val cachedAudioState = cached(idx) {
                    if (released || workerJob?.isActive == false) {
                        throw kotlinx.coroutines.CancellationException("配音合成任务已取消")
                    }
                }
                if (cachedAudioState != null) {
                    // A cache file is usable only after its header and exact payload length are
                    // validated. Preserve empty failure markers separately from corrupt entries.
                    markCuePrepared(idx, hasAudio = cachedAudioState)
                    if (!released && cachedAudioState && firstSynthesisReported.compareAndSet(false, true)) {
                        runCatching {
                            val audioFromMemory = memoryAudio(idx)
                            if (audioFromMemory != null) {
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
                                    if (!released) onFirstSynthesisReady?.invoke(idx, sampleCount, sampleRate)
                                }
                            }
                        }.onFailure {
                            android.util.Log.w("DubbingEngine", "Could not publish cached-PCM diagnostic", it)
                        }
                    }
                    bumpPrepared()
                    continue
                }
                // 只配中文译文；未翻译的行写空标记（原声顶上，且进度能连续推进）
                val text = line.zh
                if (text.isNullOrBlank()) {
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
                    markCuePrepared(idx, hasAudio = false)
                    bumpPrepared()
                    continue
                }
                try {
                    // One cue must use one synthesis-rate snapshot for its primary attempt,
                    // adaptive retry and cache identity even if Settings changes mid-request.
                    val synthesisSpeed = speed
                    val synthesisStartedAtNanos = System.nanoTime()
                    var synthesisCallCount = 0
                    var audio: io.translatetube.local.tts.TtsAudio? = null
                    var lastFailure: Exception? = null
                    for (attempt in 0..1) {
                        try {
                            synthesisCallCount++
                            val generated = tts!!.synthCancellable(text, synthesisSpeed, line.speakerId, ::checkSynthesisActive)
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
                                        if (released || workerJob?.isActive == false) {
                                            throw kotlinx.coroutines.CancellationException("配音合成任务已取消")
                                        }
                                    },
                                )
                            ) {
                                throw IllegalStateException(
                                    "所选引擎返回静音、无效采样或不支持的采样率（${generated.sampleRate} Hz）"
                                )
                            }
                            audio = generated
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
                    val cueDurationMs = (line.endMs - line.startMs).coerceAtLeast(1L)
                    val fasterSpeed = DubbingSynthesisPolicy.recommendedSpeed(
                        baseSpeed = synthesisSpeed,
                        sampleCount = firstAudio.samples.size,
                        sampleRate = firstAudio.sampleRate,
                        cueDurationMs = cueDurationMs,
                    )
                    // Prefer regenerating a long sentence with the same selected engine at a
                    // higher but bounded speech rate before trimming it to the video time window.
                    // If adaptive synthesis fails or the provider ignores speed, keep the first audio.
                    val readyAudio = if (fasterSpeed > synthesisSpeed + 0.05f) {
                        try {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            synthesisCallCount++
                            val faster = tts!!.synthCancellable(text, fasterSpeed, line.speakerId, ::checkSynthesisActive)
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            // Apply the same finite, audible-PCM validation to the adaptive
                            // retry as to the original synthesis. A non-empty array may still be
                            // silent or contain NaN/Infinity, and must never replace good audio.
                            val fasterIsValid = DubbingPcmValidation.isValidAudio(
                                faster.sampleRate,
                                faster.samples,
                                checkActive = {
                                    if (released || workerJob?.isActive == false) {
                                        throw kotlinx.coroutines.CancellationException("配音合成任务已取消")
                                    }
                                },
                            )
                            val fasterIsShorter = fasterIsValid &&
                                faster.samples.size.toDouble() / faster.sampleRate <
                                firstAudio.samples.size.toDouble() / firstAudio.sampleRate * 0.98
                            if (fasterIsShorter) faster else firstAudio
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "DubbingEngine",
                                "Adaptive speech-rate retry failed for line ${idx + 1}; keeping first synthesis",
                                e,
                            )
                            firstAudio
                        }
                    } else firstAudio
                    val synthesisElapsedMs = (System.nanoTime() - synthesisStartedAtNanos)
                        .coerceAtLeast(0L) / 1_000_000L
                    if (synthesisElapsedMs >= 500L || synthesisCallCount > 1) {
                        android.util.Log.d(
                            "DubbingEngine",
                            "TTS performance: engine=${tts?.name} line=${idx + 1}/${lines.size} " +
                                "chars=${text.length} synthMs=${synthesisElapsedMs} calls=${synthesisCallCount}",
                        )
                    }
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
                        reportSynthesisError(
                            "第 ${idx + 1} 句已合成，但缓存写入失败，且该句音频超出可用内存缓存容量，无法安全播放。请释放存储空间后重试。"
                        )
                        markCuePrepared(idx, hasAudio = false)
                        bumpPrepared()
                        continue
                    }
                    markCuePrepared(idx, hasAudio = true)
                    if (!released && firstSynthesisReported.compareAndSet(false, true)) {
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
                    // A released session must never write a failure marker into a newer session's cache.
                    if (released) throw kotlinx.coroutines.CancellationException("配音引擎已释放")
                    // After one retry, publish the failure and allow video playback to continue.
                    val detail = e.message ?: e.javaClass.simpleName
                    android.util.Log.e("DubbingEngine", "synth fail line $idx using selected engine ${tts?.name}", e)
                    // A playback cache miss may have already requested a retry while this cue
                    // was being synthesized. Defer terminal error reporting until that queued
                    // attempt has run, otherwise a successful immediate retry leaves a false warning.
                    val retryRequestedWhileActive = synchronized(pending) {
                        retryAfterActiveSynthesis.contains(idx)
                    }
                    if (DubbingRetryQueuePolicy.shouldKeepCuePendingAfterFailure(retryRequestedWhileActive)) {
                        // The queued retry is real work, not terminal silence. Keeping readiness
                        // pending prevents tick() from showing a false silent-cue/error state.
                        markCuePending(idx)
                        bumpPrepared()
                        continue
                    }
                    if (requestTtsFallback(idx, detail)) {
                        // The asynchronous service callback will install the fallback and requeue
                        // this cue. Stop before synthesizing another line with the failed engine.
                        markCuePrepared(idx, hasAudio = false)
                        bumpPrepared()
                        continue
                    }
                    reportSynthesisError("第 ${idx + 1} 句，${tts?.name ?: "所选引擎"}合成失败：$detail")
                    // Do not persist a zero-sample marker for a failed synthesis. A transient
                    // service/voice failure should be retried in a future session, not cached as silence.
                    markCuePrepared(idx, hasAudio = false)
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
                    if (retryInitiallyRequested && translatedCue && !released && workerJob?.isActive != false) {
                        try {
                            hasReusableAudio = cached(idx) {
                                if (released || workerJob?.isActive == false) {
                                    throw kotlinx.coroutines.CancellationException("配音重试状态检查已取消")
                                }
                            } == true
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // Cancellation/reconfiguration owns the next queue. Do not enqueue from
                            // this stale worker, but still clear activeSynthesisIndex below.
                        } catch (cacheError: Exception) {
                            android.util.Log.w("DubbingEngine", "Could not validate retry PCM cache", cacheError)
                        }
                    }
                    synchronized(pending) {
                        if (activeSynthesisIndex == idx) activeSynthesisIndex = -1
                        val retryWasRequested = retryAfterActiveSynthesis.remove(idx)
                        if (
                            DubbingRetryQueuePolicy.shouldRequeueAfterActiveSynthesis(
                                retryRequested = retryWasRequested,
                                translatedCue = translatedCue,
                                hasReusableAudio = hasReusableAudio,
                                workerActive = workerJob?.isActive == true,
                                released = released,
                                fallbackRequested = fallbackRequested.get(),
                                alreadyQueued = pending.contains(idx),
                            )
                        ) {
                            pending.offerFirst(idx)
                        }
                    }
                }
            }

            // Close the empty-queue race: a cue can be enqueued after pollFirst() returned null
            // but before this Job completes. Publish this worker as idle under the same lock used
            // by startSynthLoop(), then restart if work arrived during that window.
            val completedWorker = kotlinx.coroutines.currentCoroutineContext()[Job]
            val shouldRestart = synchronized(synthStartLock) {
                if (synthJob === completedWorker) synthJob = null
                !released && !fallbackRequested.get() && pending.isNotEmpty()
            }
            if (shouldRestart) startSynthLoop()
            }
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
            markCuePrepared(failedIndex, hasAudio = false)
            bumpPrepared()
        }
        reportSynthesisError("系统 TTS 配音失败，自动切换备用引擎也未成功：$detail")
    }

    private fun reportSynthesisError(message: String) {
        if (!released && synthesisErrorReported.compareAndSet(false, true)) {
            // A UI/notification callback must never terminate the synthesis worker itself.
            runCatching { onSynthesisError?.invoke(message) }
                .onFailure {
                    android.util.Log.w("DubbingEngine", "Synthesis-error callback failed", it)
                }
        }
    }

    private fun publishReadinessProgress(snapshot: DubbingReadinessProgressSnapshot) {
        _preparedCount.value = snapshot.preparedCount
        _preparedUntil.value = snapshot.preparedUntil
    }

    private fun markCuePrepared(idx: Int, hasAudio: Boolean) {
        readinessProgress.markPrepared(idx, hasAudio, lines.size, ::publishReadinessProgress)
    }

    private fun markCuePending(idx: Int) {
        readinessProgress.markPending(idx, lines.size, ::publishReadinessProgress)
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
        val cueDurationMs = ((line?.endMs ?: 0L) - (line?.startMs ?: 0L)).coerceAtLeast(0L)
        return File(
            cacheDir,
            DubbingCacheIdentity.fileName(
                lineIndex = idx,
                speakerId = line?.speakerId ?: -1,
                engineKey = tts?.cacheKey ?: tts?.name ?: "unknown",
                text = line?.zh ?: "",
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
        if (memoryAudio(idx) != null) {
            checkActive()
            return true
        }
        val file = cacheFile(idx)
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
        if (released || idx !in lines.indices) return
        var ensureExistingQueueWorker = false
        val shouldScheduleWorker = synchronized(pending) {
            when (
                DubbingRetryQueuePolicy.decide(
                    index = idx,
                    activeIndex = activeSynthesisIndex,
                    alreadyQueued = pending.contains(idx),
                )
            ) {
                DubbingRetryQueueDecision.IGNORE -> false
                DubbingRetryQueueDecision.DEFER_UNTIL_ACTIVE_CUE_FINISHES -> {
                    retryAfterActiveSynthesis.add(idx)
                    false
                }
                DubbingRetryQueueDecision.ENSURE_WORKER -> {
                    ensureExistingQueueWorker = true
                    false
                }
                DubbingRetryQueueDecision.ENQUEUE -> {
                    pending.offerFirst(idx)
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
                }
            }
        } catch (error: Exception) {
            // A cancelled or failed write must not leave a partial file in the active cache dir.
            runCatching { temp.delete() }
            throw error
        }
        return target.name
    }

    /** 返回 (samples, sampleRate)；严格校验长度，避免损坏缓存导致超大分配或崩溃。 */
    private fun load(
        idx: Int,
        isCurrent: () -> Boolean = { true },
    ): Pair<FloatArray, Int>? {
        if (!isCurrent()) return null
        val f = cacheFile(idx)
        val inMemory = memoryAudio(idx)
        if (inMemory != null) {
            if (!isCurrent()) return null
            // Media3's LRU evictor tracks actual cache touches. Keep our file-backed cache's
            // mtime aligned when playback hits memory so frequently reused PCM isn't evicted as
            // if it had not been used since the original synthesis.
            touchCacheFile(f)
            return inMemory.samples to inMemory.sampleRate
        }
        val cacheStamp = DubbingCacheFileStamp.capture(f)
        if (!validCache(f)) {
            // A newer seek/session owns the player now; do not delete or requeue a cache on its behalf.
            if (!isCurrent()) return null
            // Cache files can disappear after attach() validates them. Treat this as recoverable
            // pending work, not a permanent silent cue; keep source audio audible while regenerating.
            deleteCacheIfUnchanged(f, cacheStamp)
            markCuePending(idx)
            queueSynthesisRetry(idx)
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
                if (!isCurrent()) return null
                val samples = FloatArray(n)
                var hasAudibleSample = false
                for (sampleIndex in 0 until n) {
                    // PCM may contain millions of samples. Cancel stale playback loads in bounded
                    // chunks so a seek doesn't wait for an entire large file to be materialized.
                    if ((sampleIndex and 0x1fff) == 0 && !isCurrent()) return null
                    val sample = d.readFloat()
                    if (!sample.isFinite()) throw java.io.IOException("PCM 缓存包含无效采样值")
                    if (!hasAudibleSample && DubbingPcmValidation.isAudibleSample(sample)) {
                        hasAudibleSample = true
                    }
                    samples[sampleIndex] = sample
                }
                if (!isCurrent()) return null
                if (n > 0 && !hasAudibleSample) {
                    throw java.io.IOException("PCM 缓存包含静音音频")
                }
                val hasAudio = n > 0
                if (!DubbingCacheAudioPolicy.isCompatibleWithTranslation(
                        hasAudio = hasAudio,
                        translatedText = lines.getOrNull(idx)?.zh,
                    )
                ) {
                    // A stale empty marker must not suppress speech for a translated cue, and
                    // a non-empty cache must not override a deliberately blank translation.
                    throw java.io.IOException("PCM 缓存音频状态与字幕译文不一致")
                }
                if (!hasAudio) {
                    markCuePrepared(idx, hasAudio = false)
                } else {
                    rememberMemoryAudio(idx, samples, sr, f.name)
                    markCuePrepared(idx, hasAudio = true)
                }
                touchCacheFile(f, cacheStamp)
                samples to sr
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A seek can invalidate the reader while the file is being read. That is not cache
            // corruption; leave the file untouched and let the next playAt() load its own cue.
            if (!isCurrent()) return null
            // A corrupt cache is recoverable too. Roll back displayed progress and leave the cue
            // pending so it can be synthesized again instead of being treated as permanently silent.
            deleteCacheIfUnchanged(f, cacheStamp)
            markCuePending(idx)
            queueSynthesisRetry(idx)
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
    private fun awaitStaticChunkBoundary(
        sourceTrack: AudioTrack,
        chunkStartSample: Int,
        chunkEndSample: Int,
        generation: Int,
    ): Boolean {
        val expectedSampleCount = (chunkEndSample - chunkStartSample).coerceAtLeast(0).toLong()
        val waitStartedMs = android.os.SystemClock.elapsedRealtime()
        while (true) {
            if (!DubbingChunkTransitionPolicy.canCommit(
                    released = released,
                    currentGeneration = playbackGeneration.get(),
                    expectedGeneration = generation,
                    currentTrackIsSource = track === sourceTrack,
                )
            ) return false

            val playedSamples = sourceTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (playedSamples >= expectedSampleCount ||
                sourceTrack.playState != AudioTrack.PLAYSTATE_PLAYING
            ) return true
            if (android.os.SystemClock.elapsedRealtime() - waitStartedMs >= 5_000L) {
                throw DubbingChunkBoundaryTimeoutException()
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
        initialHeadPosition: Int = 0,
    ) {
        if (chunkEndSample >= samples.size || chunkEndSample <= chunkStartSample) return
        val chunkSampleCount = chunkEndSample - chunkStartSample
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
                                val targetSkip = DubbingTiming.lateStartSkipSamples(
                                    preparedAtMs = preparedAtMs,
                                    currentPositionMs = latestPosition,
                                    syncRate = syncRate,
                                    sampleRate = sampleRate,
                                    availableSampleCount = samples.size,
                                )
                                val nextStart = maxOf(chunkEndSample, targetSkip)
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
                                    initialHeadPosition = headOffset,
                                )
                                track = nextTrack
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
                                android.util.Log.w("DubbingEngine", "Unable to advance long dubbing cue", error)
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

    /** 播放某一行（先停上一行）。
     *  AudioTrack 创建/写入/播放（含文件 load IO）全部在专用线程，不阻塞调用方（主线程 tick）。 */
    fun playAt(
        idx: Int,
        volume: Float = 1.0f,
        positionMs: Long? = null,
        playerSpeed: Float = 1.0f,
        playbackAllowed: (() -> Boolean)? = null,
    ) {
        if (released) return
        val line = lines.getOrNull(idx) ?: return
        if (!DubbingTimeline.isPlayableCue(line)) return
        stop()
        activeVolume = volume.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f
        // Seed the asynchronous startup with the current ExoPlayer speed; ticker updates can
        // override it while cache loading / AudioTrack creation is still in progress.
        activePlayerSpeed = DubbingTiming.normalizePlayerSpeed(playerSpeed)
        val generation = synchronized(playbackControlLock) {
            activePlaybackGuard = playbackAllowed
            playbackStartedLine = -1
            _currentLine.value = idx
            playbackGeneration.incrementAndGet()
        }

        val timelineTts = tts as? TimelineTtsPlayback
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
            val estimatedSpeechMs = spokenText.length * 180L
            val timingRate = (estimatedSpeechMs.toFloat() / cueDurationMs.toFloat())
                .coerceIn(0.75f, 2.2f)
            val timelineSpeed = DubbingTiming.normalizePlayerSpeed(playerSpeed)
            val speechRate = io.translatetube.local.tts.TtsSpeechRatePolicy.normalize(
                speed * timingRate * timelineSpeed,
            )
            val utteranceId = "timeline-${System.nanoTime()}-$idx-$generation"
            val accepted = timelineTts.speakOnTimeline(
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
                        if (allowed) playbackStartedLine = idx
                        allowed
                    }
                    if (stillCurrent) {
                        runCatching { onPlaybackStarted?.invoke(idx) }
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
                    if (!error.isNullOrBlank()) {
                        val stillCurrent = synchronized(playbackControlLock) {
                            !released &&
                                playbackGeneration.get() == generation &&
                                _currentLine.value == idx
                        }
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
            if (!accepted) {
                markCuePrepared(idx, hasAudio = false)
                synchronized(playbackControlLock) {
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
                var created: AudioTrack? = null
                try {
                    val loaded = load(idx) {
                        !released && playbackGeneration.get() == generation
                    } ?: run {
                        // A missing/corrupt cache is queued for re-synthesis by load(). Do not
                        // leave this cue selected as if its audio were active: otherwise later
                        // ticks see the same index and never call playAt() after the retry finishes.
                        if (!released && playbackGeneration.get() == generation &&
                            _currentLine.value == idx
                        ) {
                            playbackStartedLine = -1
                            _currentLine.value = -1
                        }
                        return@execute
                    }
                    // Android 9/OEM AudioTrack implementations can reject common TTS rates such
                    // as 22.05/24 kHz in static mode. Normalize speech output to 16 kHz mono PCM
                    // before constructing a track; lower 8 kHz engines remain at their native rate.
                    val outputPcm = DubbingOutputPcmPolicy.normalize(loaded.first, loaded.second) {
                        if (released || playbackGeneration.get() != generation) {
                            throw kotlinx.coroutines.CancellationException("配音输出准备已过期")
                        }
                    }
                    val samples = outputPcm.samples
                    val sr = outputPcm.sampleRate
                    if (samples.isEmpty() || released || playbackGeneration.get() != generation) {
                        if (samples.isEmpty()) clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }

                    val position = DubbingStartPositionPolicy.choose(
                        lineIndex = idx,
                        lineStartMs = line.startMs,
                        lineEndMs = line.endMs,
                        requestedPositionMs = positionMs,
                        latestCursor = latestTimelineCursor,
                    ) ?: run {
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    val currentSpeed = DubbingTiming.normalizePlayerSpeed(activePlayerSpeed)
                    val plan = DubbingTiming.createPlan(
                        sampleCount = samples.size,
                        sampleRate = sr,
                        lineStartMs = line.startMs,
                        lineEndMs = line.endMs,
                        positionMs = position,
                        playerSpeed = currentSpeed,
                    )
                    val playbackSamples = DubbingTiming.slice(samples, plan, sr) {
                        if (released || playbackGeneration.get() != generation) {
                            throw kotlinx.coroutines.CancellationException("配音切片已过期")
                        }
                    }
                    // Keep this line selected when its speech has naturally ended. Otherwise a
                    // 100 ms player tick would repeatedly restart the same cue.
                    if (playbackSamples.isEmpty() || released || playbackGeneration.get() != generation) {
                        if (playbackSamples.isEmpty()) clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }

                    var chunkStart = 0
                    val requestedFirstEnd = nextStaticChunkEnd(chunkStart, playbackSamples.size)
                    val firstChunk = createStaticChunkWithFallback(
                        samples = playbackSamples,
                        startSample = chunkStart,
                        requestedEndSample = requestedFirstEnd,
                        sampleRate = sr,
                    )
                    var chunkEnd = firstChunk.endSample
                    var newTrack = firstChunk.track
                    created = newTrack
                    if (released || playbackGeneration.get() != generation) {
                        newTrack.release()
                        created = null
                        return@execute
                    }

                    // Android/OEM static AudioTrack buffers are bounded. Create the first chunk
                    // only, then append further static chunks at marker boundaries for long cues.
                    // Whenever the player has advanced during setup, skip expired samples before
                    // playback rather than starting stale speech.
                    var skippedSamples = 0
                    fun rebuildChunkAt(startSample: Int) {
                        val oldTrack = newTrack
                        try { oldTrack.pause(); oldTrack.flush(); oldTrack.release() } catch (_: Exception) {}
                        chunkStart = startSample.coerceIn(0, playbackSamples.size)
                        val requestedEnd = nextStaticChunkEnd(chunkStart, playbackSamples.size)
                        if (requestedEnd <= chunkStart) throw IllegalStateException("配音分段长度无效")
                        val createdChunk = createStaticChunkWithFallback(
                            samples = playbackSamples,
                            startSample = chunkStart,
                            requestedEndSample = requestedEnd,
                            sampleRate = sr,
                        )
                        newTrack = createdChunk.track
                        chunkEnd = createdChunk.endSample
                        created = newTrack
                        if (track === oldTrack) track = newTrack
                        newTrack.setVolume(activeVolume.coerceIn(0.0f, 1.0f))
                        runCatching {
                            newTrack.setPlaybackParams(
                                PlaybackParams().setSpeed(activePlaybackRate).setPitch(1.0f)
                            )
                        }.onFailure {
                            android.util.Log.w("DubbingEngine", "Audio playback-rate adjustment unavailable", it)
                        }
                    }

                    fun alignToLatestTimelinePosition(): Boolean {
                        val latestPosition = DubbingStartPositionPolicy.choose(
                            lineIndex = idx,
                            lineStartMs = line.startMs,
                            lineEndMs = line.endMs,
                            requestedPositionMs = position,
                            latestCursor = latestTimelineCursor,
                        ) ?: return false
                        val targetSkip = DubbingTiming.lateStartSkipSamples(
                            preparedAtMs = position,
                            currentPositionMs = latestPosition,
                            syncRate = plan.syncRate,
                            sampleRate = sr,
                            availableSampleCount = playbackSamples.size,
                        )
                        if (targetSkip >= playbackSamples.size) return false
                        if (targetSkip > skippedSamples) {
                            if (targetSkip >= chunkEnd) {
                                rebuildChunkAt(targetSkip)
                            } else {
                                val result = newTrack.setPlaybackHeadPosition(targetSkip - chunkStart)
                                if (result != AudioTrack.SUCCESS) {
                                    throw IllegalStateException(
                                        "无法按最新播放位置校准配音采样头：AudioTrack 状态 $result"
                                    )
                                }
                            }
                            skippedSamples = targetSkip
                        }
                        return true
                    }

                    if (!alignToLatestTimelinePosition()) {
                        newTrack.release()
                        created = null
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }

                    // Playback speed may have changed while PCM was loading. Combine the cue's
                    // synchronization rate with the newest player speed instead of a stale value.
                    val playbackRate = (
                        plan.syncRate * DubbingTiming.normalizePlayerSpeed(activePlayerSpeed)
                    ).coerceIn(0.5f, 4.0f)
                    runCatching {
                        newTrack.setPlaybackParams(
                            PlaybackParams().setSpeed(playbackRate).setPitch(1.0f)
                        )
                    }.onFailure {
                        android.util.Log.w("DubbingEngine", "Audio playback-rate adjustment unavailable", it)
                    }

                    track?.let { oldTrack ->
                        if (oldTrack !== newTrack) {
                            try { oldTrack.pause(); oldTrack.flush(); oldTrack.release() } catch (_: Exception) {}
                        }
                    }
                    track = newTrack
                    activeSyncRate = plan.syncRate
                    activePlaybackRate = playbackRate
                    if (released || playbackGeneration.get() != generation) {
                        if (track === newTrack) track = null
                        newTrack.release()
                        created = null
                        return@execute
                    }
                    newTrack.setVolume(activeVolume.coerceIn(0.0f, 1.0f))
                    if (!alignToLatestTimelinePosition()) {
                        if (track === newTrack) track = null
                        newTrack.release()
                        created = null
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    if (released || playbackGeneration.get() != generation) {
                        if (track === newTrack) track = null
                        newTrack.release()
                        created = null
                        return@execute
                    }
                    installNextStaticChunk(
                        sourceTrack = newTrack,
                        samples = playbackSamples,
                        sampleRate = sr,
                        chunkStartSample = chunkStart,
                        chunkEndSample = chunkEnd,
                        lineIndex = idx,
                        generation = generation,
                        preparedAtMs = position,
                        syncRate = plan.syncRate,
                        lineStartMs = line.startMs,
                        lineEndMs = line.endMs,
                        // The first buffer may already have skipped elapsed speech during setup.
                        // Marker positions are track-local, so pass the exact remaining head offset
                        // instead of assuming playback starts at sample zero.
                        initialHeadPosition = (skippedSamples - chunkStart).coerceAtLeast(0),
                    )
                    if (!startTrackIfCurrent(newTrack, generation, startedLineIndex = idx)) {
                        if (track === newTrack) track = null
                        runCatching { newTrack.release() }
                        created = null
                        clearUnstartedLineIfCurrent(idx, generation)
                        return@execute
                    }
                    created = null
                    val ownsStartedTrack = synchronized(playbackControlLock) {
                        !released && playbackGeneration.get() == generation &&
                            track === newTrack && playbackStartedLine == idx &&
                            isActivePlaybackAllowedLocked()
                    }
                    if (ownsStartedTrack) {
                        runCatching { onPlaybackStarted?.invoke(idx) }
                            .onFailure {
                                android.util.Log.w("DubbingEngine", "Could not publish playback diagnostic", it)
                            }
                    }
                } catch (e: Exception) {
                    // play() can fail after this track has already been published. Clear the shared
                    // reference before releasing it so volume/speed updates never target a dead track.
                    if (created != null && track === created) track = null
                    try { created?.release() } catch (_: Exception) {}
                    // A seek or pause may retire this asynchronous request while normalization or
                    // AudioTrack initialization is in progress. Never turn its stale exception
                    // into a false TTS failure/fallback for the newly active cue.
                    if (released || playbackGeneration.get() != generation ||
                        e is kotlinx.coroutines.CancellationException
                    ) return@execute
                    // Avoid an endless 100 ms retry loop after an AudioTrack output failure.
                    // The service sees this marker and restores original video audio.
                    markCuePrepared(idx, hasAudio = false)
                    val detail = e.message ?: e.javaClass.simpleName
                    android.util.Log.w("DubbingEngine", "AudioTrack playback failed for line $idx using selected engine ${tts?.name}", e)
                    if (!requestTtsFallback(idx, detail)) {
                        reportSynthesisError("第 ${idx + 1} 句音频输出失败：$detail")
                    }
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
        invalidatePlayback()
        val active = track ?: return
        try {
            audioExec.execute {
                // stop(), seek, or release may have superseded this request while it was queued.
                // In that case the owning cleanup task remains responsible for the old track.
                if (released || track !== active) return@execute
                track = null
                try {
                    active.pause()
                    active.flush()
                    active.release()
                } catch (e: Exception) {
                    runCatching { active.release() }
                    android.util.Log.w("DubbingEngine", "Unable to release voice track after pause", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // release() owns final AudioTrack cleanup when the executor is shutting down.
            _currentLine.value = -1
        }
    }

    /** Resume only the same still-current track generation; otherwise the next video tick rebuilds it. */
    fun resumePlayback() {
        if (released) return
        val expectedGeneration = playbackGeneration.get()
        val active = track
        if (active == null) {
            // A track may have been in its creation path when pause occurred. Let the next
            // video tick start the correct line for the current timestamp.
            _currentLine.value = -1
            return
        }
        try {
            audioExec.execute {
                if (released || track !== active) return@execute
                try {
                    if (!startTrackIfCurrent(active, expectedGeneration)) return@execute
                } catch (e: Exception) {
                    if (track === active) track = null
                    _currentLine.value = -1
                    try { active.release() } catch (_: Exception) {}
                    android.util.Log.w("DubbingEngine", "Unable to resume voice track", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            _currentLine.value = -1
        }
    }

    fun stop() {
        invalidatePlayback()
        (tts as? TimelineTtsPlayback)?.stopTimelineSpeech()
        val old = track
        track = null
        if (released) {
            try { old?.release() } catch (_: Exception) {}
            return
        }
        if (old != null) {
            try {
                audioExec.execute {
                    try { old.pause(); old.flush(); old.release() } catch (_: Exception) {}
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                try { old.release() } catch (_: Exception) {}
            }
        }
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
        speed = next
        restartSynthesisForCurrentConfiguration()
    }

    private fun restartSynthesisForCurrentConfiguration() {
        if (released) return
        val generation = cacheInitGeneration.incrementAndGet()
        val oldCacheInit = cacheInitJob
        val oldSynthesis = synchronized(synthStartLock) { synthJob }
        oldCacheInit?.cancel()
        oldSynthesis?.cancel()
        synchronized(mem) { mem.clear() }
        resetReadinessForCurrentConfiguration()
        synchronized(pending) {
            pending.clear()
            priorityTarget = -1
        }

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
            if (tts is TimelineTtsPlayback) {
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
        if (tts is TimelineTtsPlayback) return
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
        val rate = (activeSyncRate * normalized).coerceIn(0.5f, 4.0f)
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
    /** 该行音频是否已可播放（纯内存判断，供 100ms tick 高频调用，绝不做磁盘 IO） */
    fun isPrepared(idx: Int): Boolean = readiness.isPrepared(idx)
    fun isSilent(idx: Int): Boolean = readiness.isSilent(idx)
    /** 正在播放的行（-1=无） */
    fun currentLineIdx(): Int = _currentLine.value

    /** True only after this cue's AudioTrack actually started, not while PCM is merely ready. */
    fun hasStartedPlaybackFor(idx: Int): Boolean =
        !released && idx >= 0 && _currentLine.value == idx && playbackStartedLine == idx

    fun release() {
        // Publish the released state under the same lock used to install cache files. Any write
        // that commits first is complete; any later write sees released and aborts.
        synchronized(cacheWriteLock) {
            if (released) return
            released = true
        }
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
        private val cacheInstallLock = Any()
        private val nextCacheWriterId = AtomicInteger(0)
        private const val TEMP_PCM_AGE_MS = 6L * 60L * 60L * 1_000L
        // Keep each native static track well below Android's common 1 MiB static-buffer ceiling.
        // Keep static chunks small for Android 9/OEM AudioTrack implementations with
        // restrictive buffer limits. At 24 kHz this is at most one second of audio.
        private const val MAX_STATIC_TRACK_SAMPLES = 24_000
    }
}
