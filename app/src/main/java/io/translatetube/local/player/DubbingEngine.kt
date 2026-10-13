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
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ConcurrentHashMap
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

    private var tts: TtsEngine? = null
    /** Called when the selected engine fails; the service reports this without switching engines. */
    @Volatile var onSynthesisError: ((String) -> Unit)? = null
    private val synthesisErrorReported = AtomicBoolean(false)
    private var lines: List<SubLine> = emptyList()
    private var speed = 1.0f
    @Volatile private var track: AudioTrack? = null
    @Volatile private var activeSyncRate = 1.0f
    @Volatile private var activePlayerSpeed = 1.0f
    @Volatile private var activePlaybackRate = 1.0f
    private val playbackGeneration = AtomicInteger(0)
    @Volatile private var released = false
    /** AudioTrack 操作专用单线程：write(MODE_STATIC) 数据拷贝耗时，绝不放主线程 */
    private val audioExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tt-audio").apply { priority = Thread.MAX_PRIORITY }
    }
    private val noAudio = ConcurrentHashMap.newKeySet<Int>()
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
        synthesisErrorReported.set(false)
        _totalLines.value = lines.size
        _preparedUntil.value = 0
        noAudio.clear()
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
            if (validCache(file) && file.length() == 8L) {
                if (lines[i].zh.isNullOrBlank()) {
                    // Empty output is expected for a subtitle line with no translated text.
                    noAudio.add(i)
                } else {
                    // An empty PCM created by a previous failed TTS run is not a successful
                    // cache hit. Remove it and retry using the currently selected engine.
                    file.delete()
                }
            }
            if (!cached(i)) pending.offerLast(i)
        }
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
            while (!pending.isEmpty()) {
                val idx = synchronized(pending) { pending.pollFirst() } ?: break
                if (cached(idx)) { bumpPrepared(idx); continue }
                val line = lines.getOrNull(idx) ?: continue
                // 只配中文译文；未翻译的行写空标记（原声顶上，且进度能连续推进）
                val text = line.zh
                if (text.isNullOrBlank()) {
                    writeCache(idx, 16000, FloatArray(0))
                    noAudio.add(idx)
                    bumpPrepared(idx)
                    continue
                }
                try {
                    var audio: io.translatetube.local.tts.TtsAudio? = null
                    var lastFailure: Exception? = null
                    for (attempt in 0..1) {
                        try {
                            val generated = tts!!.synth(text, speed, line.speakerId)
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
                    val readyAudio = audio ?: throw (lastFailure
                        ?: IllegalStateException("配音引擎没有返回有效音频"))
                    writeCache(idx, readyAudio.sampleRate, readyAudio.samples)
                    noAudio.remove(idx)
                    synchronized(mem) { mem[idx] = readyAudio.samples to readyAudio.sampleRate }
                    bumpPrepared(idx)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // After one retry, publish the failure and allow video playback to continue.
                    android.util.Log.e("DubbingEngine", "synth fail line $idx using selected engine ${tts?.name}", e)
                    reportSynthesisError("第 ${idx + 1} 句，${tts?.name ?: "所选引擎"}合成失败：${e.message ?: e.javaClass.simpleName}")
                    try {
                        writeCache(idx, 16000, FloatArray(0))
                        noAudio.add(idx)
                        bumpPrepared(idx)
                    } catch (writeError: Exception) {
                        android.util.Log.e("DubbingEngine", "cache fallback fail line $idx", writeError)
                    }
                }
            }
        }
    }

    private fun reportSynthesisError(message: String) {
        if (synthesisErrorReported.compareAndSet(false, true)) {
            onSynthesisError?.invoke(message)
        }
    }

    private fun bumpPrepared(idx: Int) {
        // preparedUntil = 连续就绪的最大行数
        var n = _preparedUntil.value
        while (n < lines.size && cached(n)) n++
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
        // Include the configured default voice as well as engine/speed so changing the
        // Kokoro default cannot reuse PCM synthesized with the previous voice.
        val engineHash = fingerprint("${tts?.cacheKey ?: tts?.name ?: "unknown"}-v6-speed-${speed}")
        return File(cacheDir, "${idx}-v${voice}-e${engineHash}-t${textHash}.pcm")
    }
    private fun validCache(file: File): Boolean = file.isFile && file.length() >= 8L

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
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw java.io.IOException("无法替换 PCM 缓存")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("无法安装 PCM 缓存")
        }
    }

    /** 返回 (samples, sampleRate)；严格校验长度，避免损坏缓存导致超大分配或崩溃。 */
    private fun load(idx: Int): Pair<FloatArray, Int>? {
        synchronized(mem) { mem[idx]?.let { return it } }
        val f = cacheFile(idx)
        if (!validCache(f)) return null
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
                    noAudio.add(idx)
                } else {
                    synchronized(mem) { mem[idx] = samples to sr }
                    noAudio.remove(idx)
                }
                samples to sr
            }
        } catch (e: Exception) {
            // 损坏缓存不能反复触发加载；将本行标记为无可用配音并回退原声。
            f.delete()
            noAudio.add(idx)
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
                    newTrack.setVolume(volume.coerceIn(0.0f, 1.0f))
                    newTrack.play()
                    created = null
                } catch (e: Exception) {
                    try { created?.release() } catch (_: Exception) {}
                    // Avoid an endless 100 ms retry loop after an AudioTrack output failure.
                    // The service sees this marker and restores original video audio.
                    if (playbackGeneration.get() == generation) noAudio.add(idx)
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

    /** 当前时间对应行下标（二分）；-1=间隙 */
    fun lineAt(ms: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].startMs <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (ans >= 0 && ms < lines[ans].endMs) return ans
        return -1
    }

    fun line(idx: Int): SubLine? = lines.getOrNull(idx)
    fun allLines(): List<SubLine> = lines
    fun setSpeed(s: Float) { speed = s }

    /** Update the active voice without changing the selected TTS engine or its cached PCM. */
    fun updatePlayerSpeed(speed: Float) {
        val normalized = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
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
    fun isPrepared(idx: Int): Boolean = idx >= 0 && idx < _preparedUntil.value && idx !in noAudio
    fun isSilent(idx: Int): Boolean = idx >= 0 && idx < _preparedUntil.value && idx in noAudio
    /** 正在播放的行（-1=无） */
    fun currentLineIdx(): Int = _currentLine.value
    fun release() {
        if (released) return
        released = true
        playbackGeneration.incrementAndGet()
        synthJob?.cancel()
        tts?.close()
        tts = null
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
}
