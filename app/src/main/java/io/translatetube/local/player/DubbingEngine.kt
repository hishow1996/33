package io.translatetube.local.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import io.translatetube.local.subs.SubLine
import io.translatetube.local.tts.TtsEngine
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
class DubbingEngine(
    ctx: Context,
    private val videoKey: String,
    private val scope: CoroutineScope,
) {
    private val cacheDir = File(ctx.cacheDir, "dub/$videoKey").apply { mkdirs() }
    /** 内存缓存：行号 → (采样点, 采样率) */
    private val mem = object : LinkedHashMap<Int, Pair<FloatArray, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<FloatArray, Int>>): Boolean {
            return size > 48 // 内存最多 48 行，其余走文件
        }
    }

    @Volatile private var tts: TtsEngine? = null
    /** Called when the selected engine fails; the service reports this without switching engines. */
    @Volatile var onSynthesisError: ((String) -> Unit)? = null
    private val synthesisErrorReported = AtomicBoolean(false)
    private var lines: List<SubLine> = emptyList()
    private var timeline = DubbingTimeline(emptyList())
    private var speed = 1.0f
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
    private val ttsCloseSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val readiness = DubbingReadiness()
    private val pending = LinkedBlockingDeque<Int>()
    @Volatile private var priorityTarget = -1
    private var synthJob: Job? = null

    private val _preparedUntil = MutableStateFlow(0)   // 已合成到第几行（0-based 含义：前 N 行就绪）
    val preparedUntil: StateFlow<Int> = _preparedUntil
    private val _totalLines = MutableStateFlow(0)
    val totalLines: StateFlow<Int> = _totalLines
    private val _currentLine = MutableStateFlow(-1)
    val currentLine: StateFlow<Int> = _currentLine

    fun attach(tts: TtsEngine, lines: List<SubLine>) {
        this.tts = tts
        this.lines = lines
        this.timeline = DubbingTimeline(lines)
        synthesisErrorReported.set(false)
        _totalLines.value = lines.size
        _preparedUntil.value = 0
        readiness.clear()
        pending.clear()
        priorityTarget = -1
        // 清内存缓存：防止旧会话同 idx 行的音频串台。
        synchronized(mem) { mem.clear() }
        // 译文或音色变化会生成新的缓存名，并清除本视频已失效的旧 PCM，避免旧配音串台及缓存无限累积。
        val expectedCacheNames = lines.indices.map { cacheFile(it).name }.toSet()
        cacheDir.listFiles()?.filter {
            (it.name.endsWith(".pcm") || it.name.endsWith(".pcm.tmp")) &&
                it.name !in expectedCacheNames
        }?.forEach { it.delete() }
        // 从未缓存的最早行开始排
        for (i in lines.indices) {
            val file = cacheFile(i)
            val cacheHasAudio = inspectCache(file)
            val blankTranslation = lines[i].zh.isNullOrBlank()
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
        // Existing cached lines count toward progress even when no synthesis job is needed.
        bumpPrepared()
        startSynthLoop()
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
        synthJob?.cancel()
        synthJob = scope.launch(Dispatchers.Default) {
            while (!released && !pending.isEmpty()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val idx = synchronized(pending) { pending.pollFirst() } ?: break
                if (cached(idx)) { bumpPrepared(); continue }
                val line = lines.getOrNull(idx) ?: continue
                // 只配中文译文；未翻译的行写空标记（原声顶上，且进度能连续推进）
                val text = line.zh
                if (text.isNullOrBlank()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    writeCache(idx, 16000, FloatArray(0))
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
                            if (generated.sampleRate !in 8_000..192_000 || generated.samples.isEmpty()) {
                                throw IllegalStateException(
                                    "所选引擎返回空音频或无效采样率（${generated.sampleRate} Hz）"
                                )
                            }
                            audio = generated
                            break
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastFailure = e
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
                            val fasterIsValid = faster.sampleRate in 8_000..192_000 &&
                                faster.samples.isNotEmpty()
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
                    writeCache(idx, readyAudio.sampleRate, readyAudio.samples)
                    readiness.markPrepared(idx, hasAudio = true)
                    synchronized(mem) { mem[idx] = readyAudio.samples to readyAudio.sampleRate }
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
                        writeCache(idx, 16000, FloatArray(0))
                        readiness.markPrepared(idx, hasAudio = false)
                        bumpPrepared()
                    } catch (writeError: Exception) {
                        android.util.Log.e("DubbingEngine", "cache fallback fail line $idx", writeError)
                    }
                }
            }
        }
    }

    private fun reportSynthesisError(message: String) {
        if (!released && synthesisErrorReported.compareAndSet(false, true)) {
            onSynthesisError?.invoke(message)
        }
    }

    private fun bumpPrepared() {
        // The display remains a contiguous count, but playback readiness is tracked per line.
        var n = _preparedUntil.value
        while (n < lines.size && readiness.isReady(n)) n++
        _preparedUntil.value = n
    }

    private fun cacheFile(idx: Int): File {
        val line = lines.getOrNull(idx)
        val voice = line?.speakerId ?: -1
        fun fingerprint(value: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
        // Full text + engine fingerprint prevents cache collisions and invalidates old WAV PCM.
        val textHash = fingerprint(line?.zh ?: "")
        // Adaptive synthesis depends on this cue's available time window. Include its duration
        // and bump the cache identity so pre-adaptation PCM is never mistaken for the new policy.
        val cueDurationMs = ((line?.endMs ?: 0L) - (line?.startMs ?: 0L)).coerceAtLeast(0L)
        val engineHash = fingerprint(
            "${tts?.cacheKey ?: tts?.name ?: "unknown"}-v7-speed-${speed}-cue-ms-${cueDurationMs}-adaptive"
        )
        return File(cacheDir, "${idx}-v${voice}-e${engineHash}-t${textHash}.pcm")
    }
    private fun validCache(file: File): Boolean = file.isFile && file.length() >= 8L

    /**
     * Return true for a valid non-empty PCM cache, false for a valid empty marker,
     * and null when the entry is missing/corrupt and should be synthesized again.
     */
    private fun inspectCache(file: File): Boolean? {
        if (!file.isFile) return null
        if (file.length() < 8L) {
            file.delete()
            return null
        }
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(file))).use { input ->
                val sampleRate = input.readInt()
                val sampleCount = input.readInt()
                if (sampleRate !in 8_000..192_000 || sampleCount < 0 || sampleCount > 16_000_000 ||
                    file.length() != 8L + sampleCount.toLong() * 4L
                ) {
                    throw java.io.IOException("PCM 缓存头或长度无效")
                }
                sampleCount > 0
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun cached(idx: Int): Boolean =
        synchronized(mem) { mem.containsKey(idx) } || validCache(cacheFile(idx))

    /** 原子写入缓存，防止进程中断留下半个 PCM 文件并被误判为已就绪。 */
    private fun writeCache(idx: Int, sampleRate: Int, samples: FloatArray) {
        val target = cacheFile(idx)
        val temp = File(cacheDir, target.name + ".tmp")
        temp.delete()
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(temp))).use { out ->
            out.writeInt(sampleRate)
            out.writeInt(samples.size)
            samples.forEach { out.writeFloat(it) }
            out.flush()
        }
        if (!validCache(temp) || temp.length() != 8L + samples.size.toLong() * 4L) {
            temp.delete()
            throw java.io.IOException("PCM 缓存写入不完整")
        }
        synchronized(cacheWriteLock) {
            if (released) {
                temp.delete()
                throw kotlinx.coroutines.CancellationException("配音引擎已释放，取消缓存写入")
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                throw java.io.IOException("无法替换 PCM 缓存")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw java.io.IOException("无法安装 PCM 缓存")
            }
        }
    }

    /** 返回 (samples, sampleRate)；严格校验长度，避免损坏缓存导致超大分配或崩溃。 */
    private fun load(idx: Int): Pair<FloatArray, Int>? {
        synchronized(mem) { mem[idx]?.let { return it } }
        val f = cacheFile(idx)
        if (!validCache(f)) {
            readiness.markPrepared(idx, hasAudio = false)
            return null
        }
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { d ->
                val sr = d.readInt()
                val n = d.readInt()
                if (sr <= 0 || n < 0 || n > 16_000_000 ||
                    f.length() != 8L + n.toLong() * 4L) {
                    throw java.io.IOException("PCM 缓存格式无效")
                }
                val samples = FloatArray(n) { d.readFloat() }
                if (n == 0) {
                    readiness.markPrepared(idx, hasAudio = false)
                } else {
                    synchronized(mem) { mem[idx] = samples to sr }
                    readiness.markPrepared(idx, hasAudio = true)
                }
                samples to sr
            }
        } catch (e: Exception) {
            // 损坏缓存不能反复触发加载；将本行标记为无可用配音并回退原声。
            f.delete()
            readiness.markPrepared(idx, hasAudio = false)
            null
        }
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
        _currentLine.value = idx
        val generation = playbackGeneration.incrementAndGet()
        try {
            audioExec.execute {
                var created: AudioTrack? = null
                try {
                    val (samples, sr) = load(idx) ?: return@execute
                    if (samples.isEmpty() || released || playbackGeneration.get() != generation) return@execute

                    val position = positionMs ?: line.startMs
                    val plan = DubbingTiming.createPlan(
                        sampleCount = samples.size,
                        sampleRate = sr,
                        lineStartMs = line.startMs,
                        lineEndMs = line.endMs,
                        positionMs = position,
                        playerSpeed = playerSpeed,
                    )
                    val playbackSamples = DubbingTiming.slice(samples, plan, sr)
                    // Keep this line selected when its speech has naturally ended. Otherwise a
                    // 100 ms player tick would repeatedly restart the same cue.
                    if (playbackSamples.isEmpty() || released || playbackGeneration.get() != generation) {
                        return@execute
                    }

                    val newTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(playbackSamples.size * 4)
                        .build()
                    created = newTrack
                    val written = newTrack.write(
                        playbackSamples, 0, playbackSamples.size, AudioTrack.WRITE_BLOCKING
                    )
                    if (written != playbackSamples.size || released || playbackGeneration.get() != generation) {
                        newTrack.release()
                        created = null
                        return@execute
                    }

                    runCatching {
                        newTrack.setPlaybackParams(
                            PlaybackParams().setSpeed(plan.playbackRate).setPitch(1.0f)
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
                    activePlayerSpeed = playerSpeed.coerceIn(0.5f, 2.0f)
                    activePlaybackRate = plan.playbackRate
                    if (released || playbackGeneration.get() != generation) {
                        if (track === newTrack) track = null
                        newTrack.release()
                        created = null
                        return@execute
                    }
                    newTrack.setVolume(activeVolume.coerceIn(0.0f, 1.0f))
                    newTrack.play()
                    created = null
                } catch (e: Exception) {
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

    /** Pause/resume the live voice track with the video; preserve playback head on pause. */
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
            active.pause()
        } catch (e: Exception) {
            if (track === active) track = null
            _currentLine.value = -1
            try { active.release() } catch (_: Exception) {}
            android.util.Log.w("DubbingEngine", "Unable to pause voice track", e)
        }
    }

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
            active.play()
        } catch (e: Exception) {
            if (track === active) track = null
            _currentLine.value = -1
            try { active.release() } catch (_: Exception) {}
            android.util.Log.w("DubbingEngine", "Unable to resume voice track", e)
        }
    }

    fun stop() {
        playbackGeneration.incrementAndGet()
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
        val activeSynthesis = synthJob
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
        if (old != null) {
            try {
                audioExec.execute {
                    try { old.pause(); old.flush(); old.release() } catch (_: Exception) {}
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                try { old.release() } catch (_: Exception) {}
            }
        }
        // 正常关闭队列：已排队任务会看到 generation/released 状态并退出，清理任务仍能执行。
        audioExec.shutdown()
    }

    /** Release immediately, then await TTS disposal before constructing a replacement engine. */
    suspend fun releaseAndJoin() {
        release()
        ttsCloseSignal.await()
    }
}
