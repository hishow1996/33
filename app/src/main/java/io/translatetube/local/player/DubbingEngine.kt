package io.translatetube.local.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import io.translatetube.local.subs.SubLine
import io.translatetube.local.tts.TtsEngine
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
 * 行级预合成配音引擎：
 * 后台按字幕顺序逐行合成 PCM 并缓存（内存 LRU + 文件），
 * 播放侧 playAt(index) 用 AudioTrack 即时切行。
 */
private class DubbingPcmLimitException(message: String) : IllegalStateException(message)

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
    /** Called when the selected engine fails; the service reports this without switching engines. */
    @Volatile var onSynthesisError: ((String) -> Unit)? = null
    /** Diagnostic callback: the first successful PCM result for this session. */
    @Volatile var onFirstSynthesisReady: ((index: Int, sampleCount: Int, sampleRate: Int) -> Unit)? = null
    /** Diagnostic callback after AudioTrack.play() succeeds, not merely when synthesis is ready. */
    @Volatile var onPlaybackStarted: ((index: Int) -> Unit)? = null
    private val synthesisErrorReported = AtomicBoolean(false)
    private val firstSynthesisReported = AtomicBoolean(false)
    private var lines: List<SubLine> = emptyList()
    private var timeline = DubbingTimeline(emptyList())
    private var speed = 1.0f
    /** Latest player position; async PCM loading must not start speech at an old timestamp. */
    @Volatile private var latestTimelineCursor = DubbingTimelineCursor()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var activeSyncRate = 1.0f
    @Volatile private var activePlayerSpeed = 1.0f
    @Volatile private var activePlaybackRate = 1.0f
    @Volatile private var activeVolume = 1.0f
    private val playbackGeneration = AtomicInteger(0)
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
    private val pending = LinkedBlockingDeque<Int>()
    @Volatile private var priorityTarget = -1
    @Volatile private var synthJob: Job? = null
    @Volatile private var cacheInitJob: Job? = null
    private val synthStartLock = Any()
    private val cacheInitGeneration = AtomicInteger(0)

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

    fun attach(tts: TtsEngine, lines: List<SubLine>) {
        // Publish the TTS instance, subtitle list and cache-init Job atomically with release().
        // Otherwise a concurrent retirement could miss this TTS instance or the newly launched job.
        synchronized(cacheWriteLock) {
            if (released) throw IllegalStateException("配音引擎已释放，不能重新绑定字幕")
            this.tts = tts
            this.lines = lines.toList()
            this.timeline = DubbingTimeline(this.lines)
            synthesisErrorReported.set(false)
            firstSynthesisReported.set(false)
            _totalLines.value = this.lines.size
            _preparedUntil.value = 0
            _preparedCount.value = 0
            readiness.clear()
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

                    val expectedCacheNames = this@DubbingEngine.lines.indices
                        .map { cacheFile(it).name }.toHashSet()
                    // Coordinate installs with cache pruning across engine instances. Temporary
                    // staging files use per-engine names and are removed only after they go stale.
                    synchronized(cacheInstallLock) {
                        val nowMs = System.currentTimeMillis()
                        val existing = cacheDir.listFiles().orEmpty()
                        for (file in existing) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (released || generation != cacheInitGeneration.get()) return@launch
                            when {
                                file.name.endsWith(".pcm") && file.name !in expectedCacheNames ->
                                    file.delete()
                                isStalePcmTemp(file, nowMs) ->
                                    file.delete()
                            }
                        }
                    }

                    for (i in this@DubbingEngine.lines.indices) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (released || generation != cacheInitGeneration.get()) return@launch
                        val file = cacheFile(i)
                        val cacheHasAudio = inspectCache(file, ::checkCacheInitActive)
                        val blankTranslation = this@DubbingEngine.lines[i].zh.isNullOrBlank()
                        when {
                            blankTranslation && cacheHasAudio == false ->
                                readiness.markPrepared(i, hasAudio = false)
                            blankTranslation && cacheHasAudio == true -> {
                                // A blank translation must never reuse a non-empty cache entry.
                                file.delete()
                                pending.offerLast(i)
                            }
                            !blankTranslation && cacheHasAudio == true ->
                                readiness.markPrepared(i, hasAudio = true)
                            !blankTranslation && cacheHasAudio == false -> {
                                // Empty PCM from a failed old run isn't a successful cache hit.
                                file.delete()
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
                            .filterNot { readiness.isReady(it) }
                            .forEach { pending.offerLast(it) }
                        bumpPrepared()
                        startSynthLoop()
                    }
                }
            }
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
                val idx = synchronized(pending) { pending.pollFirst() } ?: break
                val workerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
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
                    readiness.markPrepared(idx, hasAudio = cachedAudioState)
                    if (!released && cachedAudioState && firstSynthesisReported.compareAndSet(false, true)) {
                        runCatching {
                            val audioFromMemory = synchronized(mem) { mem[idx] }
                            if (audioFromMemory != null) {
                                onFirstSynthesisReady?.invoke(
                                    idx,
                                    audioFromMemory.first.size,
                                    audioFromMemory.second,
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
                val line = lines.getOrNull(idx) ?: continue
                // 只配中文译文；未翻译的行写空标记（原声顶上，且进度能连续推进）
                val text = line.zh
                if (text.isNullOrBlank()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    // An empty translation is intentionally silent. Persisting its empty marker is
                    // only an optimization; a full cache must not terminate the whole TTS queue.
                    try {
                        writeCache(idx, 16000, FloatArray(0), checkActive = ::checkSynthesisActive)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "DubbingEngine",
                            "Could not persist empty-translation marker for line ${idx + 1}; keeping session readiness",
                            e,
                        )
                    }
                    readiness.markPrepared(idx, hasAudio = false)
                    bumpPrepared()
                    continue
                }
                try {
                    var audio: io.translatetube.local.tts.TtsAudio? = null
                    var lastFailure: Exception? = null
                    for (attempt in 0..1) {
                        try {
                            val generated = tts!!.synth(text, speed, line.speakerId)
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
                        baseSpeed = speed,
                        sampleCount = firstAudio.samples.size,
                        sampleRate = firstAudio.sampleRate,
                        cueDurationMs = cueDurationMs,
                    )
                    // Prefer regenerating a long sentence with the same selected engine at a
                    // higher but bounded speech rate before trimming it to the video time window.
                    // If adaptive synthesis fails or the provider ignores speed, keep the first audio.
                    val readyAudio = if (fasterSpeed > speed + 0.05f) {
                        try {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val faster = tts!!.synth(text, fasterSpeed, line.speakerId)
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
                    val maxUsefulSamples = DubbingSynthesisPolicy.maxUsefulSampleCount(
                        sampleRate = readyAudio.sampleRate,
                        cueDurationMs = cueDurationMs,
                    )
                    val boundedSamples = if (maxUsefulSamples in 1 until readyAudio.samples.size) {
                        readyAudio.samples.copyOf(maxUsefulSamples).takeIf {
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
                    writeCache(idx, readyAudio.sampleRate, boundedSamples, checkActive = ::checkSynthesisActive)
                    readiness.markPrepared(idx, hasAudio = true)
                    synchronized(mem) { mem[idx] = boundedSamples to readyAudio.sampleRate }
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
                    android.util.Log.e("DubbingEngine", "synth fail line $idx using selected engine ${tts?.name}", e)
                    reportSynthesisError("第 ${idx + 1} 句，${tts?.name ?: "所选引擎"}合成失败：${e.message ?: e.javaClass.simpleName}")
                    try {
                        writeCache(idx, 16000, FloatArray(0), checkActive = ::checkSynthesisActive)
                    } catch (writeError: Exception) {
                        if (writeError is kotlinx.coroutines.CancellationException) throw writeError
                        // Session readiness is independent of persistence. Even when storage is
                        // unavailable, finish this cue as silent so playback falls back to source
                        // audio and the worker can continue with later subtitles.
                        android.util.Log.e("DubbingEngine", "cache fallback fail line $idx", writeError)
                    }
                    readiness.markPrepared(idx, hasAudio = false)
                    bumpPrepared()
                }
            }

            // Close the empty-queue race: a cue can be enqueued after pollFirst() returned null
            // but before this Job completes. Publish this worker as idle under the same lock used
            // by startSynthLoop(), then restart if work arrived during that window.
            val completedWorker = kotlinx.coroutines.currentCoroutineContext()[Job]
            val shouldRestart = synchronized(synthStartLock) {
                if (synthJob === completedWorker) synthJob = null
                !released && pending.isNotEmpty()
            }
            if (shouldRestart) startSynthLoop()
            }
        }
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

    private fun markCuePending(idx: Int) {
        readiness.markPending(idx)
        _preparedCount.value = readiness.readyCount().coerceIn(0, lines.size)
        // If a previously ready cue becomes invalid, progress must no longer claim that its
        // contiguous prefix is complete. Later ready cues remain available independently.
        if (idx >= 0 && idx < _preparedUntil.value) _preparedUntil.value = idx
    }

    private fun bumpPrepared() {
        // Keep both the contiguous prefix and the actual ready-cue count. The latter is used for
        // progress after a seek prioritizes a later subtitle before an earlier gap is filled.
        _preparedCount.value = readiness.readyCount().coerceIn(0, lines.size)
        var n = _preparedUntil.value
        while (n < lines.size && readiness.isReady(n)) n++
        _preparedUntil.value = n
    }

    /**
     * Keep recently used subtitle PCM for quick repeat playback without allowing old videos
     * to grow the app cache forever. The currently attached video is never evicted here.
     */
    private fun pruneOldDubbingCaches() {
        val root = cacheRootDir
        if (!root.isDirectory) return
        val nowMs = System.currentTimeMillis()
        val staleTemps = root.walkTopDown()
            .filter { file ->
                isStalePcmTemp(file, nowMs) &&
                    file.parentFile?.absolutePath != cacheDir.absolutePath
            }
            .toList()
        synchronized(cacheInstallLock) {
            staleTemps.forEach { temp ->
                if (temp.delete()) temp.parentFile?.takeIf { it != root && it.list().isNullOrEmpty() }?.delete()
            }
        }

        val pcmFiles = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".pcm") }
            .toList()
        val entries = pcmFiles.map { file ->
            DubbingCacheEntry(
                path = file.absolutePath,
                bytes = file.length(),
                lastModified = file.lastModified(),
                isCurrentVideo = file.parentFile?.absolutePath == cacheDir.absolutePath,
            )
        }
        for (entry in DubbingCachePolicy.filesToEvict(entries)) {
            val file = File(entry.path)
            if (file.delete()) {
                file.parentFile?.takeIf { it != root && it.list().isNullOrEmpty() }?.delete()
            }
        }
    }

    private fun cacheFile(idx: Int): File {
        val line = lines.getOrNull(idx)
        val cueDurationMs = ((line?.endMs ?: 0L) - (line?.startMs ?: 0L)).coerceAtLeast(0L)
        return File(
            cacheDir,
            DubbingCacheIdentity.fileName(
                lineIndex = idx,
                speakerId = line?.speakerId ?: -1,
                engineKey = tts?.cacheKey ?: tts?.name ?: "unknown",
                text = line?.zh ?: "",
                speed = speed,
                cueDurationMs = cueDurationMs,
            ),
        )
    }
    private fun validCache(file: File): Boolean = file.isFile && file.length() >= 8L

    /**
     * Return true for a valid non-empty PCM cache, false for a valid empty marker,
     * and null when the entry is missing/corrupt and should be synthesized again.
     */
    private fun inspectCache(
        file: File,
        checkActive: () -> Unit = {},
    ): Boolean? {
        if (!file.isFile) return null
        if (file.length() < 8L) {
            file.delete()
            return null
        }
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file))).use { input ->
                val sampleRate = input.readInt()
                val sampleCount = input.readInt()
                if (sampleRate !in 8_000..192_000 || sampleCount < 0 || sampleCount > DubbingPcmValidation.MAX_SAMPLE_COUNT ||
                    file.length() != 8L + sampleCount.toLong() * 4L
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
                    sampleCount == 0 -> false // Valid explicit empty marker.
                    hasAudibleSample -> true
                    else -> throw java.io.IOException("PCM 缓存只有静音采样")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** Update the file-backed LRU timestamp when a PCM row is actually reused for playback. */
    private fun touchCacheFile(file: File) {
        runCatching {
            if (file.isFile) file.setLastModified(System.currentTimeMillis())
        }
    }

    /** true = valid audio, false = valid empty marker, null = missing or corrupt cache. */
    private fun cached(
        idx: Int,
        checkActive: () -> Unit = {},
    ): Boolean? {
        if (synchronized(mem) { mem.containsKey(idx) }) {
            checkActive()
            return true
        }
        return inspectCache(cacheFile(idx), checkActive)
    }

    /**
     * A cache can become unavailable after attach() has marked its row ready. Requeue it without
     * starting a second synthesis worker. Joining the worker observed here closes the small race
     * where it has polled an empty queue but has not yet completed its Job.
     */
    private fun queueSynthesisRetry(idx: Int) {
        if (released || idx !in lines.indices) return
        synchronized(pending) {
            if (!pending.contains(idx)) pending.offerFirst(idx)
        }
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
        checkActive: () -> Unit = {},
    ) {
        val target = cacheFile(idx)
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
    }

    /** 返回 (samples, sampleRate)；严格校验长度，避免损坏缓存导致超大分配或崩溃。 */
    private fun load(
        idx: Int,
        isCurrent: () -> Boolean = { true },
    ): Pair<FloatArray, Int>? {
        if (!isCurrent()) return null
        val f = cacheFile(idx)
        val memoryAudio = synchronized(mem) { mem[idx] }
        if (memoryAudio != null) {
            if (!isCurrent()) return null
            // Media3's LRU evictor tracks actual cache touches. Keep our file-backed cache's
            // mtime aligned when playback hits memory so frequently reused PCM isn't evicted as
            // if it had not been used since the original synthesis.
            touchCacheFile(f)
            return memoryAudio
        }
        if (!validCache(f)) {
            // A newer seek/session owns the player now; do not delete or requeue a cache on its behalf.
            if (!isCurrent()) return null
            // Cache files can disappear after attach() validates them. Treat this as recoverable
            // pending work, not a permanent silent cue; keep source audio audible while regenerating.
            f.delete()
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
                if (n == 0) {
                    readiness.markPrepared(idx, hasAudio = false)
                } else {
                    synchronized(mem) { mem[idx] = samples to sr }
                    readiness.markPrepared(idx, hasAudio = true)
                }
                f.setLastModified(System.currentTimeMillis())
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
            f.delete()
            markCuePending(idx)
            queueSynthesisRetry(idx)
            null
        }
    }

    /**
     * Blocking AudioTrack writes may report fewer samples than requested. Continue from the
     * returned offset instead of treating a positive partial write as a failed engine.
     */
    private fun writeFully(track: AudioTrack, samples: FloatArray): Int {
        var offset = 0
        while (offset < samples.size) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return written
            if (written == 0) return offset
            offset += written
        }
        return offset
    }

    private fun writeFully(track: AudioTrack, samples: ShortArray): Int {
        var offset = 0
        while (offset < samples.size) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return written
            if (written == 0) return offset
            offset += written
        }
        return offset
    }

    /**
     * Float PCM is preferred for fidelity, but some OEM audio stacks fail to initialize or write
     * PCM_FLOAT tracks. Retry once using the broadly supported PCM_16BIT format before reporting
     * a dubbing-output failure, so a valid TTS result is not discarded on older devices.
     */
    private fun createFilledAudioTrack(samples: FloatArray, sampleRate: Int): AudioTrack {
        fun attributes() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        var floatTrack: AudioTrack? = null
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

            if (floatTrack.state == AudioTrack.STATE_INITIALIZED) {
                val written = writeFully(floatTrack, samples)
                if (written == samples.size) return floatTrack
                android.util.Log.w(
                    "DubbingEngine",
                    "PCM_FLOAT AudioTrack wrote ${written}/${samples.size}; trying PCM_16BIT",
                )
            } else {
                android.util.Log.w("DubbingEngine", "PCM_FLOAT AudioTrack failed to initialize; trying PCM_16BIT")
            }
        } catch (e: Exception) {
            android.util.Log.w("DubbingEngine", "PCM_FLOAT output unavailable; trying PCM_16BIT", e)
        }
        try { floatTrack?.release() } catch (_: Exception) {}

        var pcm16Track: AudioTrack? = null
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
            if (pcm16Track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("PCM_16BIT AudioTrack 初始化失败")
            }
            val written = writeFully(pcm16Track, pcm16)
            if (written != pcm16.size) {
                throw IllegalStateException("PCM_16BIT AudioTrack 写入不完整：${written}/${pcm16.size} 个采样点")
            }
            android.util.Log.i(
                "DubbingEngine",
                "Using PCM_16BIT fallback for ${samples.size} samples at ${sampleRate} Hz",
            )
            return pcm16Track
        } catch (e: Exception) {
            try { pcm16Track?.release() } catch (_: Exception) {}
            throw IllegalStateException(
                "PCM_FLOAT 与 PCM_16BIT 音轨均无法输出中文配音",
                e,
            )
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
    ) {
        if (released) return
        val line = lines.getOrNull(idx) ?: return
        stop()
        activeVolume = volume.coerceIn(0.0f, 1.0f)
        playbackStartedLine = -1
        // Seed the asynchronous startup with the current ExoPlayer speed; ticker updates can
        // override it while cache loading / AudioTrack creation is still in progress.
        activePlayerSpeed = DubbingTiming.normalizePlayerSpeed(playerSpeed)
        _currentLine.value = idx
        val generation = playbackGeneration.incrementAndGet()
        try {
            audioExec.execute {
                var created: AudioTrack? = null
                try {
                    val (samples, sr) = load(idx) {
                        !released && playbackGeneration.get() == generation
                    } ?: return@execute
                    if (samples.isEmpty() || released || playbackGeneration.get() != generation) return@execute

                    val position = DubbingStartPositionPolicy.choose(
                        lineIndex = idx,
                        lineStartMs = line.startMs,
                        lineEndMs = line.endMs,
                        requestedPositionMs = positionMs,
                        latestCursor = latestTimelineCursor,
                    ) ?: return@execute
                    val currentSpeed = DubbingTiming.normalizePlayerSpeed(activePlayerSpeed)
                    val plan = DubbingTiming.createPlan(
                        sampleCount = samples.size,
                        sampleRate = sr,
                        lineStartMs = line.startMs,
                        lineEndMs = line.endMs,
                        positionMs = position,
                        playerSpeed = currentSpeed,
                    )
                    val playbackSamples = DubbingTiming.slice(samples, plan, sr)
                    // Keep this line selected when its speech has naturally ended. Otherwise a
                    // 100 ms player tick would repeatedly restart the same cue.
                    if (playbackSamples.isEmpty() || released || playbackGeneration.get() != generation) {
                        return@execute
                    }

                    val newTrack = createFilledAudioTrack(playbackSamples, sr)
                    created = newTrack
                    // Cancellation/session replacement is expected; a partial/failed write is not.
                    // The latter is rejected inside createFilledAudioTrack so original audio is
                    // restored instead of leaving this cue marked ready while its output is silent.
                    if (released || playbackGeneration.get() != generation) {
                        newTrack.release()
                        created = null
                        return@execute
                    }

                    // The player can advance while the static AudioTrack buffer is being filled.
                    // Re-check the timeline and advance the static playback head to skip elapsed
                    // samples, rather than speaking the old words late. The track is MODE_STATIC
                    // and mono, so the head position is expressed in the same frames as PCM samples.
                    var skippedSamples = 0
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
                        // No intelligible audio remains when the head would reach the end of
                        // this bounded cue buffer; do not start a clipped syllable after the cue.
                        if (targetSkip >= playbackSamples.size) return false
                        if (targetSkip > skippedSamples) {
                            val result = newTrack.setPlaybackHeadPosition(targetSkip)
                            if (result != AudioTrack.SUCCESS) {
                                throw IllegalStateException(
                                    "无法按最新播放位置校准配音采样头：AudioTrack 状态 $result"
                                )
                            }
                            skippedSamples = targetSkip
                        }
                        return true
                    }

                    if (!alignToLatestTimelinePosition()) {
                        newTrack.release()
                        created = null
                        return@execute
                    }

                    // Playback speed may have changed while PCM was loading. Combine the cue's
                    // synchronization rate with the newest player speed instead of overwriting it
                    // with a stale value captured before AudioTrack creation.
                    val playbackRate = (
                        plan.syncRate * DubbingTiming.normalizePlayerSpeed(activePlayerSpeed)
                    ).coerceIn(0.5f, 4.0f)
                    runCatching {
                        newTrack.setPlaybackParams(
                            PlaybackParams().setSpeed(playbackRate).setPitch(1.0f)
                        )
                    }.onFailure {
                        // If a provider rejects dynamic rates, keep the voice audible at native speed.
                        android.util.Log.w("DubbingEngine", "Audio playback-rate adjustment unavailable", it)
                    }

                    track?.let { old ->
                        try { old.pause(); old.flush(); old.release() } catch (_: Exception) {}
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
                    // Re-check once more immediately before play() in case another ticker
                    // update arrived while parameters/volume were applied.
                    if (!alignToLatestTimelinePosition()) {
                        if (track === newTrack) track = null
                        newTrack.release()
                        created = null
                        return@execute
                    }
                    if (released || playbackGeneration.get() != generation) {
                        if (track === newTrack) track = null
                        newTrack.release()
                        created = null
                        return@execute
                    }
                    newTrack.play()
                    created = null
                    if (!released && playbackGeneration.get() == generation) {
                        playbackStartedLine = idx
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
                    // Avoid an endless 100 ms retry loop after an AudioTrack output failure.
                    // The service sees this marker and restores original video audio.
                    if (playbackGeneration.get() == generation) readiness.markPrepared(idx, hasAudio = false)
                    android.util.Log.w("DubbingEngine", "AudioTrack playback failed for line $idx using selected engine ${tts?.name}", e)
                    reportSynthesisError("第 ${idx + 1} 句音频输出失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            _currentLine.value = -1
        }
    }

    /** Pause the live voice track without blocking the player/UI thread. */
    fun pausePlayback() {
        if (released) return
        // Invalidate a line that is still loading/being created so it cannot start after pause.
        playbackGeneration.incrementAndGet()
        val active = track
        if (active == null) {
            _currentLine.value = -1
            return
        }
        try {
            audioExec.execute {
                // stop(), seek, or release may have superseded this request while it was queued.
                if (released || track !== active) return@execute
                try {
                    active.pause()
                } catch (e: Exception) {
                    if (track === active) track = null
                    _currentLine.value = -1
                    try { active.release() } catch (_: Exception) {}
                    android.util.Log.w("DubbingEngine", "Unable to pause voice track", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // release() owns the final AudioTrack cleanup when the executor is shutting down.
            _currentLine.value = -1
        }
    }

    /** Resume a paused track on the same serialized executor as creation, writes, and release. */
    fun resumePlayback() {
        if (released) return
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
                    active.play()
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
        playbackGeneration.incrementAndGet()
        playbackStartedLine = -1
        _currentLine.value = -1
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
    fun setSpeed(s: Float) { speed = s.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f }

    /** Update the active voice volume immediately when the user toggles original audio. */
    fun updateVolume(volume: Float) {
        val normalized = volume.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f
        if (kotlin.math.abs(normalized - activeVolume) < 0.01f) return
        activeVolume = normalized
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
        playbackGeneration.incrementAndGet()
        val oldTts = tts
        tts = null
        cacheInitGeneration.incrementAndGet()
        cacheInitJob?.cancel()
        // Serialize the task snapshot with startSynthLoop(). Without this lock, release()
        // could read a null/old Job while a new worker is being installed, then close the native
        // TTS engine underneath that just-started synthesis call.
        val activeSynthesis = synchronized(synthStartLock) { synthJob }
        activeSynthesis?.cancel()
        when {
            oldTts == null -> ttsCloseSignal.complete(Unit)
            oldTts is io.translatetube.local.tts.SystemTts -> {
                // System TTS uses a latch-backed callback. close() marks it closed before waking
                // synth(), so it can abort without decoding a partial WAV.
                try { oldTts.close() } finally { ttsCloseSignal.complete(Unit) }
            }
            activeSynthesis != null && !activeSynthesis.isCompleted -> {
                // Kokoro performs blocking native work. Defer native release until it unwinds.
                activeSynthesis.invokeOnCompletion {
                    try { oldTts.close() } finally { ttsCloseSignal.complete(Unit) }
                }
            }
            else -> {
                try { oldTts.close() } finally { ttsCloseSignal.complete(Unit) }
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
    }
}
