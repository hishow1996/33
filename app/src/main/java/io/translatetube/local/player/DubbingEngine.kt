package io.translatetube.local.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.translatetube.local.subs.SubLine
import io.translatetube.local.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ConcurrentHashMap
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
    private var lines: List<SubLine> = emptyList()
    private var speed = 1.0f
    @Volatile private var track: AudioTrack? = null
    private val playbackGeneration = AtomicInteger(0)
    @Volatile private var released = false
    /** AudioTrack 操作专用单线程：write(MODE_STATIC) 数据拷贝耗时，绝不放主线程 */
    private val audioExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tt-audio").apply { priority = Thread.MAX_PRIORITY }
    }
    private val noAudio = ConcurrentHashMap.newKeySet<Int>()
    private val pending = PriorityBlockingQueue<Int>()
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
        _totalLines.value = lines.size
        _preparedUntil.value = 0
        noAudio.clear()
        pending.clear()
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
            if (validCache(file) && file.length() == 8L) noAudio.add(i)
            if (!cached(i)) pending.offer(i)
        }
        startSynthLoop()
    }

    private fun startSynthLoop() {
        synthJob?.cancel()
        synthJob = scope.launch(Dispatchers.Default) {
            while (!pending.isEmpty()) {
                val idx = pending.poll() ?: break
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
                    val audio = tts!!.synth(text, speed, line.speakerId)
                    if (audio.sampleRate <= 0 || audio.samples.isEmpty()) {
                        writeCache(idx, audio.sampleRate.coerceAtLeast(16000), FloatArray(0))
                        noAudio.add(idx)
                    } else {
                        writeCache(idx, audio.sampleRate, audio.samples)
                        noAudio.remove(idx)
                        synchronized(mem) { mem[idx] = audio.samples to audio.sampleRate }
                    }
                    bumpPrepared(idx)
                } catch (e: Exception) {
                    // 合成失败时写入空标记，保证准备进度不会永久卡在这一行；该行回退原声。
                    android.util.Log.e("DubbingEngine", "synth fail line $idx", e)
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
        // v4 forces a clean cache after fixing the old escaped interpolation bug.
        val engineHash = fingerprint("${tts?.name ?: "unknown"}-v4")
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
    fun playAt(idx: Int, volume: Float = 1.0f) {
        if (released) return
        stop()
        _currentLine.value = idx
        val generation = playbackGeneration.incrementAndGet()
        try {
            audioExec.execute {
                var created: AudioTrack? = null
                try {
                    val (samples, sr) = load(idx) ?: return@execute
                    // 空标记表示此行没有可用配音，保留原声，不创建零长度 AudioTrack。
                    if (samples.isEmpty() || released || playbackGeneration.get() != generation) return@execute
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
                        .setBufferSizeInBytes(samples.size * 4)
                        .build()
                    created = newTrack
                    val written = newTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (written != samples.size || released || playbackGeneration.get() != generation) {
                        newTrack.release()
                        created = null
                        return@execute
                    }
                    // 所有 AudioTrack 的创建、替换和播放都在同一线程，避免快速切行时旧音轨迟到启动。
                    track?.let { old ->
                        try { old.pause(); old.flush(); old.release() } catch (_: Exception) {}
                    }
                    track = newTrack
                    if (released || playbackGeneration.get() != generation) {
                        if (track === newTrack) track = null
                        newTrack.release()
                        created = null
                        return@execute
                    }
                    newTrack.setVolume(volume)
                    newTrack.play()
                    created = null
                } catch (e: Exception) {
                    try { created?.release() } catch (_: Exception) {}
                    // 音频设备/AudioTrack 初始化失败时回退原声，避免 UI 误以为配音仍在播放而静音原声。
                    if (playbackGeneration.get() == generation) {
                        noAudio.add(idx)
                        if (_currentLine.value == idx) _currentLine.value = -1
                    }
                    android.util.Log.w("DubbingEngine", "AudioTrack playback failed for line $idx", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            _currentLine.value = -1
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
