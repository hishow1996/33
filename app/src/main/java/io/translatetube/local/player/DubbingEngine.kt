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
    /** AudioTrack 操作专用单线程：write(MODE_STATIC) 数据拷贝耗时，绝不放主线程 */
    private val audioExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tt-audio").apply { priority = Thread.MAX_PRIORITY }
    }
    private val synthCounter = AtomicInteger(0)
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
        synthCounter.set(0)
        pending.clear()
        // 清内存缓存：防止旧会话同 idx 行的音频串台
        synchronized(mem) { mem.clear() }
        // 从未缓存的最早行开始排
        for (i in lines.indices) if (!cached(i)) pending.offer(i)
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
                    val f = File(cacheDir, "$idx.pcm")
                    f.outputStream().use { o ->
                        java.io.DataOutputStream(o).let { d -> d.writeInt(16000); d.writeInt(0) }
                    }
                    bumpPrepared(idx); continue
                }
                try {
                    val audio = tts!!.synth(text, speed)
                    val f = File(cacheDir, "$idx.pcm")
                    f.outputStream().use { o ->
                        java.io.DataOutputStream(o).let { d ->
                            d.writeInt(audio.sampleRate)
                            d.writeInt(audio.samples.size)
                            audio.samples.forEach { d.writeFloat(it) }
                        }
                    }
                    synchronized(mem) { mem[idx] = audio.samples to audio.sampleRate }
                    bumpPrepared(idx)
                } catch (e: Exception) {
                    // 合成失败：跳过该行继续（真机日志排查）
                    android.util.Log.e("DubbingEngine", "synth fail line $idx", e)
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

    private fun cached(idx: Int): Boolean =
        synchronized(mem) { mem.containsKey(idx) } || File(cacheDir, "$idx.pcm").exists()

    /** 返回 (samples, sampleRate) */
    private fun load(idx: Int): Pair<FloatArray, Int>? {
        synchronized(mem) { mem[idx]?.let { return it } }
        val f = File(cacheDir, "$idx.pcm")
        if (!f.exists()) return null
        return try {
            f.inputStream().use { ins ->
                java.io.DataInputStream(ins).let { d ->
                    val sr = d.readInt()
                    val n = d.readInt()
                    val samples = FloatArray(n) { d.readFloat() }
                    synchronized(mem) { mem[idx] = samples to sr }
                    samples to sr
                }
            }
        } catch (e: Exception) { null }
    }

    /** 播放某一行（先停上一行）。
     *  AudioTrack 创建/写入/播放（含文件 load IO）全部在专用线程，不阻塞调用方（主线程 tick）。 */
    fun playAt(idx: Int, volume: Float = 1.0f) {
        stop()
        _currentLine.value = idx
        audioExec.execute {
            try {
                val (samples, sr) = load(idx) ?: return@execute
                val t = AudioTrack.Builder()
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
                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                t.setVolume(volume)
                t.play()
                track = t
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        val old = track
        track = null
        if (old != null) {
            audioExec.execute {
                try { old.pause(); old.flush(); old.release() } catch (_: Exception) {}
            }
        }
        _currentLine.value = -1
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
    fun isPrepared(idx: Int): Boolean = idx < _preparedUntil.value
    /** 正在播放的行（-1=无） */
    fun currentLineIdx(): Int = _currentLine.value
    fun release() {
        synthJob?.cancel(); stop()
        tts?.close(); tts = null
        audioExec.shutdownNow()
    }
}
