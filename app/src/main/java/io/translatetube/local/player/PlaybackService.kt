package io.translatetube.local.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.translatetube.local.R
import io.translatetube.local.asr.AudioExtract
import io.translatetube.local.asr.WhisperEngine
import io.translatetube.local.asr.vadSegments
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.data.SourceType
import io.translatetube.local.data.VideoEntity
import io.translatetube.local.subs.SubLine
import io.translatetube.local.subs.SubtitleParsers
import io.translatetube.local.translate.Crypto
import io.translatetube.local.translate.EngineFactory
import io.translatetube.local.translate.LangMap
import io.translatetube.local.translate.MlKitEngine
import io.translatetube.local.translate.TranslationEngine
import io.translatetube.local.tts.KokoroTts
import io.translatetube.local.tts.TtsEngine
import io.translatetube.local.yt.NewPipeRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

enum class Phase { IDLE, PARSING, DOWNLOADING_AUDIO, TRANSCRIBING, TRANSLATING, SYNTHESIZING, READY, ERROR }

data class PlayerUiState(
    val phase: Phase = Phase.IDLE,
    val progress: String = "",
    val key: String = "",
    val title: String = "",
    val channel: String = "",
    val thumbUrl: String? = null,
    val durationMs: Long = 0,
    val lines: List<SubLine> = emptyList(),
    val currentLine: Int = -1,
    val preparedUntil: Int = 0,
    val totalLines: Int = 0,
    val dubbingOn: Boolean = true,
    val muteOriginal: Boolean = true,
    val audioOnly: Boolean = false,
    val error: String? = null,
    val sourceLang: String = "",
    val engineName: String = "",
    val isPlaying: Boolean = false,
    val videoAspect: Float = 16f / 9f,   // 视频真实宽高比（竖屏视频自适应）
    val noModelHint: String? = null,     // 方案 B：模型未下载引导提示
)

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var pm: PlayerManager
    private var session: MediaSession? = null
    private var dubbing: DubbingEngine? = null
    private var whisper: WhisperEngine? = null
    private var prepJob: Job? = null
    private var videoKey = ""
    private var videoId: String? = null
    private var localUri: Uri? = null
    private var importedLines: List<SubLine>? = null
    private var lastLineIdx = Int.MIN_VALUE
    private var dubCollectJob: Job? = null

    companion object {
        @Volatile var instance: PlaybackService? = null
            private set
        val ui = MutableStateFlow(PlayerUiState())
        fun state(): StateFlow<PlayerUiState> = ui
        /** 播放画面 TextureView（供悬浮窗摘取 reparent） */
        @Volatile var videoSurfaceView: android.view.TextureView? = null
        const val CH_MAIN = "playback"
    }

    /** 主线程把画面 TextureView 交给播放器 */
    fun attachSurface(tv: android.view.TextureView) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pm.player.setVideoTextureView(tv)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        pm = PlayerManager(this)
        pm.onTick = { ms -> tick(ms) }
        pm.onEnded = { ui.update { it.copy(isPlaying = false, currentLine = -1) } }
        pm.onVideoAspect = { ratio -> ui.update { it.copy(videoAspect = ratio) } }
        NotificationManagerCompat_c.create(this)
        // 【闪退修复】startForegroundService 要求 5 秒内 startForeground，否则系统强杀。
        // media3 只在播放开始时才前台化，而视频解析需 5-10 秒 → 立即手动挂前台通知顶住，
        // media3 接管时会自动用它的通知替换本条（同 service 前台槽位替换机制）。
        val boot = NotificationCompat.Builder(this, CH_MAIN)
            .setSmallIcon(io.translatetube.local.R.drawable.ic_stat_notify)
            .setContentTitle("大象翻译")
            .setContentText("正在准备…")
            .setOngoing(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    android.content.Intent(this, io.translatetube.local.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        startForeground(1001, boot)
        // 点通知栏回到应用
        val intent = android.content.Intent(this, io.translatetube.local.MainActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, pm.player)
            .setSessionActivity(pi)
            .build()
        // 通知小图标：media3 1.4 的 Builder 无 setSmallIcon，保持默认（A9 彩色通知正常）
        // 播放态/错误连到 UI 状态流
        scope.launch {
            pm.isPlaying.collect { playing -> ui.update { it.copy(isPlaying = playing) } }
        }
        scope.launch {
            pm.errorSink.collect { err ->
                if (err != null) ui.update { it.copy(error = err, phase = Phase.ERROR) }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        dubbing?.release(); whisper?.close()
        session?.release(); pm.release()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ============ 管线入口 ============
    fun open(key: String, videoId: String?, localUri: Uri?, importedLines: List<SubLine>? = null) {
        prepJob?.cancel()
        dubCollectJob?.cancel(); dubCollectJob = null
        dubbing?.release(); dubbing = null
        this.videoKey = key; this.videoId = videoId; this.localUri = localUri
        this.importedLines = importedLines
        lastLineIdx = Int.MIN_VALUE
        ui.value = PlayerUiState(phase = Phase.PARSING, key = key, progress = "解析中…")
        pm.pause()
        prepJob = scope.launch { runPipeline(key, videoId, localUri) }
    }

    /** 导入外部字幕（SRT/VTT）：解析后重新走翻译+配音 */
    fun importSubtitles(uri: Uri) {
        prepJob?.cancel()
        dubCollectJob?.cancel(); dubCollectJob = null
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw IllegalStateException("无法读取字幕文件")
            val lines = SubtitleParsers.parseAuto(text)
            if (lines.isEmpty()) throw IllegalStateException("字幕文件为空或格式不支持")
            // 清旧配音缓存：防止导入新字幕的同 idx 行播出旧音频
            java.io.File(cacheDir, "dub/$videoKey").deleteRecursively()
            // 走翻译管线但跳过重新解析视频流（保持当前播放）
            prepJob = scope.launch {
                try {
                    ui.update { it.copy(phase = Phase.TRANSLATING, progress = "翻译导入的字幕（${lines.size} 行）…") }
                    val srcLang = detectLangFromText(lines.firstOrNull()?.text ?: "")
                    translateLines(videoKey, lines, srcLang)
                    if (dubbing != null) {
                        dubbing?.release(); dubbing = null
                    }
                    val d = DubbingEngine(this@PlaybackService, videoKey, scope)
                    d.attach(makeTts(), lines)
                    dubbing = d
                    dubCollectJob = scope.launch { d.preparedUntil.collect { n -> ui.update { it.copy(preparedUntil = n) } } }
                    // 入库缓存
                    val arr = org.json.JSONArray()
                    for (l in lines) arr.put(
                        org.json.JSONObject().put("s", l.startMs).put("e", l.endMs).put("t", l.text).put("zh", l.zh ?: "")
                    )
                    if (lines.count { !it.zh.isNullOrEmpty() } > lines.size / 2) {
                        AppDatabase.get(this@PlaybackService).subtitleCacheDao().put(
                            io.translatetube.local.data.SubtitleCacheEntity("$videoKey|zh", srcLang, arr.toString(), System.currentTimeMillis())
                        )
                    }
                    ui.update { it.copy(lines = lines, totalLines = lines.size, phase = Phase.READY, progress = "") }
                    lastLineIdx = Int.MIN_VALUE
                } catch (e: Exception) {
                    ui.update { it.copy(phase = Phase.ERROR, error = "字幕导入失败: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            ui.update { it.copy(phase = Phase.ERROR, error = "字幕导入失败: ${e.message}") }
        }
    }

    /** 从首行文本猜语言（仅含 CJK 判 zh，否则 en） */
    private fun detectLangFromText(t: String): String =
        if (Regex("[\\u4e00-\\u9fff]").containsMatchIn(t)) "zh" else "en"

    private suspend fun runPipeline(key: String, videoId: String?, localUri: Uri?) {
        try {
            var meta = VideoEntity(
                key = key, videoId = videoId, localUri = localUri?.toString(), title = "", channel = "",
                durationSec = 0, thumbUrl = null, sourceType = if (videoId != null) SourceType.YOUTUBE else SourceType.LOCAL,
                addedAt = System.currentTimeMillis(), lastPlayedAt = System.currentTimeMillis(),
            )

            // ---- 1. 元信息 ----
            var srcLang = ""
            if (videoId != null) {
                val m = NewPipeRepo.resolve(videoId)
                meta = meta.copy(title = m.title, channel = m.channel, durationSec = m.durationSec, thumbUrl = m.thumbUrl)
                ui.update { it.copy(title = m.title, channel = m.channel, thumbUrl = m.thumbUrl, durationMs = m.durationSec * 1000) }
            } else {
                localUri?.let { u ->
                    // 本地视频：取真实标题/时长
                    var title = u.lastPathSegment ?: "本地视频"
                    var dur = 0L
                    try {
                        val mmr = android.media.MediaMetadataRetriever()
                        mmr.setDataSource(this, u)
                        title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                            ?.takeIf { it.isNotBlank() } ?: title
                        dur = (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?: "0").toLongOrNull() ?: 0L
                        mmr.release()
                    } catch (_: Exception) {}
                    meta = meta.copy(title = title, durationSec = dur / 1000)
                    ui.update { it.copy(title = title, durationMs = dur) }
                }
            }

            // ---- 2. 字幕行 ----
            var lines: List<SubLine>? = null
            if (videoId != null) {
                NewPipeRepo.subtitles(videoId)?.let { (l, lang) ->
                    lines = l; srcLang = lang
                }
            }
            val db = AppDatabase.get(this)
            val cached = db.subtitleCacheDao().get("$key|zh")

            if (lines == null && cached == null) {
                if (!io.translatetube.local.data.ModelFiles.asrReady(this)) {
                    // 方案 B：转写模型未下载——优雅降级（原声播放 + 引导提示），不崩
                    ui.update { it.copy(noModelHint = "该视频无字幕。下载「转写模型」后即可生成中文字幕与配音（设置 → 语音模型）") }
                } else {
                // ---- 2b. 无字幕 → 本地转写 ----
                ui.update { it.copy(phase = Phase.DOWNLOADING_AUDIO, progress = "无字幕，准备语音转写…") }
                val audioFile = if (videoId != null) {
                    val audioStream = NewPipeRepo.audioForAsr(videoId) ?: throw IllegalStateException("无法获取音频流")
                    downloadAudio(audioStream.content)
                } else {
                    localUri ?: throw IllegalStateException("无音频源")
                }
                ui.update { it.copy(phase = Phase.TRANSCRIBING, progress = "解码音频…") }
                val pcm = withContext(Dispatchers.IO) {
                    if (audioFile is File) AudioExtract.decodeTo16kMono(this@PlaybackService, null, audioFile)
                    else AudioExtract.decodeTo16kMono(this@PlaybackService, audioFile as Uri, null)
                }
                if (whisper == null) whisper = WhisperEngine(this)
                ui.update { it.copy(progress = "语音分段…") }
                val segs = withContext(Dispatchers.IO) { vadSegments(this@PlaybackService, pcm.samples) }
                if (segs.isEmpty()) throw IllegalStateException("未检测到语音内容")
                val asrLines = withContext(Dispatchers.IO) {
                    whisper!!.transcribeBySegments(pcm.samples, segs) { done, total ->
                        ui.update { it.copy(progress = "转写中 $done/$total 段") }
                    }
                }
                // 转写完成：释放临时音频文件
                if (audioFile is File) audioFile.delete()
                lines = asrLines.mapIndexed { i, (a, b, text) -> SubLine(i, a, b, text) }
                srcLang = whisper?.lastLang?.takeIf { it.isNotBlank() } ?: "en"
                }
            }

            // 用户导入的字幕优先（SRT/VTT 导入入口）
            if (importedLines != null && importedLines!!.isNotEmpty()) {
                lines = importedLines
                srcLang = detectLangFromText(importedLines!!.first().text)
            }

            if (cached != null) {
                // 缓存命中：直接还原双语行
                val arr = org.json.JSONArray(cached.linesJson)
                val restored = mutableListOf<SubLine>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    restored += SubLine(i, o.getLong("s"), o.getLong("e"), o.getString("t"), o.optString("zh").ifEmpty { null })
                }
                lines = restored
                srcLang = cached.srcLang
            } else if (lines != null) {
                // ---- 3. 翻译 ----
                val orig = lines!!
                if (orig.isNotEmpty() && srcLang != "zh") {
                    ui.update { it.copy(phase = Phase.TRANSLATING, progress = "准备翻译（${orig.size} 行）…") }
                    translateLines(key, orig, srcLang)
                }
                // 仅在译文填充率 >50% 时写缓存（避免失败结果被永久缓存）
                if (orig.count { !it.zh.isNullOrEmpty() } > orig.size / 2) {
                    val arr = org.json.JSONArray()
                    for (l in orig) arr.put(
                        org.json.JSONObject().put("s", l.startMs).put("e", l.endMs).put("t", l.text).put("zh", l.zh ?: "")
                    )
                    db.subtitleCacheDao().put(
                        io.translatetube.local.data.SubtitleCacheEntity("$key|zh", srcLang, arr.toString(), System.currentTimeMillis())
                    )
                }
            }

            val finalLines = lines ?: emptyList()
            ui.update {
                it.copy(
                    lines = finalLines, totalLines = finalLines.size, sourceLang = srcLang,
                    progress = "", phase = Phase.SYNTHESIZING,
                )
            }

            // ---- 4. 流装配 ----
            if (videoId != null) {
                val info = NewPipeRepo.streams(videoId)
                val audioStreams: List<org.schabi.newpipe.extractor.stream.AudioStream> = info.audioStreams
                val audioUrl: String = audioStreams.minByOrNull { a -> a.averageBitrate }?.content
                    ?: audioStreams.firstOrNull()?.content
                    ?: throw IllegalStateException("无音频流")
                val muxed: String? = info.videoStreams.firstOrNull { v -> !v.isVideoOnly }?.content
                val vOnly: List<Pair<String, Int>> = info.videoStreams.filter { v -> v.isVideoOnly }
                    .map { v -> v.content to (v.height * 1000 + v.bitrate) }
                withContext(Dispatchers.Main) {
                    pm.setYouTubeSource(vOnly, audioUrl, muxed, meta.title, meta.channel, meta.thumbUrl)
                }
            } else {
                withContext(Dispatchers.Main) {
                    pm.setUriSource(localUri!!, meta.title, "本地视频", null)
                }
            }

            // ---- 5. 配音引擎 ----
            if (finalLines.isNotEmpty()) {
                if (io.translatetube.local.data.ModelFiles.ttsReady(this)) {
                    val tts: TtsEngine = makeTts()
                    val d = DubbingEngine(this, key, scope)
                    d.attach(tts, finalLines)
                    dubbing = d
                    dubCollectJob = scope.launch {
                        d.preparedUntil.collect { n -> ui.update { it.copy(preparedUntil = n) } }
                    }
                } else {
                    ui.update { it.copy(noModelHint = "下载「配音模型」后即可中文配音（设置 → 语音模型）") }
                }
            }
            saveHistory(meta)
            ui.update { it.copy(phase = Phase.READY, engineName = it.engineName.ifEmpty { "本地引擎" }) }
            // 续播：上次观看超过 10 秒且距结尾超 30 秒则跳到上次位置
            val resumeSec = try {
                db.videoDao().byKey(key)?.watchedSec ?: 0L
            } catch (_: Exception) { 0L }
            val totalMs = meta.durationSec * 1000
            if (resumeSec > 10 && (totalMs <= 0 || resumeSec * 1000 < totalMs - 30_000)) {
                withContext(Dispatchers.Main) { pm.seekTo(resumeSec * 1000); pm.play() }
            } else {
                withContext(Dispatchers.Main) { pm.play() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            ui.update { it.copy(phase = Phase.ERROR, error = e.message ?: "未知错误") }
        }
    }

    /** 按活跃引擎翻译，失败自动降级：API→本地→原文 */
    private suspend fun translateLines(key: String, lines: List<SubLine>, srcLang: String) {
        val db = AppDatabase.get(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val activeId = prefs.getLong("active_api_id", -1L)
        val profiles = db.apiProfileDao().enabled()
        val tgt = "zh"
        val plain = lines.map { it.text }
        var engine: TranslationEngine? = null
        var engineName = ""

        // 手动选择的活跃 API 优先；未选（-1）或已删除则回落本地
        val active = profiles.firstOrNull { it.id == activeId }
        if (active != null) {
            try {
                engine = EngineFactory.fromProfile(active)
                engineName = active.name
            } catch (e: Exception) { /* 坏配置 → 落到本地 */ }
        }
        if (engine == null) {
            engine = MlKitEngine(); engineName = "本地引擎（离线）"
        }
        ui.update { it.copy(engineName = engineName) }

        val src = if (srcLang == "auto") "" else srcLang
        val results = try {
            engine!!.translate(plain, src, tgt)
        } catch (e: Exception) {
            if (engine !is MlKitEngine) {
                // API 失败 → 降级本地
                try {
                    engine = MlKitEngine(); ui.update { it.copy(engineName = "本地引擎（API失败降级）") }
                    engine!!.translate(plain, src, tgt)
                } catch (e2: Exception) { null }
            } else null
        }
        if (results != null) {
            for (i in lines.indices) lines[i].zh = results.getOrNull(i)
        }
    }

    private suspend fun downloadAudio(url: String): File {
        val f = File(cacheDir, "asr_audio_${System.currentTimeMillis()}.bin")
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0")
            .build()
        withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("音频下载失败 ${resp.code}")
                val total = resp.body?.contentLength() ?: -1
                resp.body!!.byteStream().use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (total > 0) {
                                ui.update { it.copy(progress = "下载音频 %.0f%%".format(got * 100f / total)) }
                            }
                        }
                    }
                }
            }
        }
        return f
    }

    private fun makeTts(): TtsEngine {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val engine = prefs.getString("tts_engine", "kokoro")
        return if (engine == "system") {
            io.translatetube.local.tts.SystemTts(this)
        } else {
            KokoroTts(this, speakerId = prefs.getInt("kokoro_speaker", 14))
        }
    }

    private suspend fun saveHistory(meta: VideoEntity) {
        val db = AppDatabase.get(this)
        val old = db.videoDao().byKey(meta.key)
        if (old != null) db.videoDao().upsert(old.copy(lastPlayedAt = System.currentTimeMillis()))
        else db.videoDao().upsert(meta.copy())
    }

    // ============ 时间轴驱动 ============
    private fun tick(ms: Long) {
        val st = ui.value
        val d = dubbing
        var cur = if (d != null) d.lineAt(ms) else -1
        if (cur < 0) {
            // 二分行列表找当前行（字幕高亮用）
            val ls = st.lines
            var lo = 0; var hi = ls.size - 1; var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (ls[mid].startMs <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            cur = ans
        }
        if (cur != lastLineIdx) {
            lastLineIdx = cur
            ui.update { it.copy(currentLine = cur) }
        }
        if (d != null && st.dubbingOn) {
            if (cur >= 0 && d.isPrepared(cur)) {
                if (d.currentLineIdx() != cur) d.playAt(cur, if (st.muteOriginal) 1f else 0.35f)
                pm.originalVolume = if (st.muteOriginal) 0f else 0.15f
            } else if (cur >= 0 && !d.isPrepared(cur)) {
                // 播放追上合成：原声顶上，等后台补齐
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = 1f
            } else {
                if (d.currentLineIdx() >= 0) d.stop()
                pm.originalVolume = if (st.muteOriginal) 0f else 1f
            }
        } else {
            pm.originalVolume = 1f
        }
    }

    // ============ UI 控制命令 ============
    fun toggleDub() {
        val nowOn = !ui.value.dubbingOn
        ui.update { it.copy(dubbingOn = nowOn) }
        if (!nowOn) { dubbing?.stop(); pm.originalVolume = 1f }
        else lastLineIdx = Int.MIN_VALUE
    }
    fun toggleMute() {
        ui.update { it.copy(muteOriginal = !it.muteOriginal) }
        lastLineIdx = Int.MIN_VALUE
    }
    fun toggleAudioOnly() {
        val nowOnly = !ui.value.audioOnly
        ui.update { it.copy(audioOnly = nowOnly) }
        // 关画面：禁视频轨（省电）；重开恢复
        pm.player.trackSelectionParameters = pm.player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, nowOnly)
            .build()
    }
    fun seek(ms: Long) { pm.seekTo(ms); lastLineIdx = Int.MIN_VALUE; dubbing?.stop() }
    fun playPause() { if (pm.isPlaying.value) pm.pause() else pm.play() }

    /** 记录观看进度（退出播放页时调用，历史列表续看用） */
    fun saveProgress() {
        val key = videoKey
        if (key.isEmpty()) return
        scope.launch {
            try {
                AppDatabase.get(this@PlaybackService).videoDao().setWatched(key, pm.position() / 1000)
            } catch (_: Exception) {}
        }
    }
    fun setSpeed(s: Float) { pm.speed(s) }
    fun position(): Long = pm.position()
    fun duration(): Long = pm.duration()

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }
}

/** 通知渠道小工具（避免直接 import 冲突） */
object NotificationManagerCompat_c {
    fun create(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        val ch = NotificationChannel(PlaybackService.CH_MAIN, "播放与配音", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        return nm
    }
}

private typealias NotificationManager = android.app.NotificationManager
