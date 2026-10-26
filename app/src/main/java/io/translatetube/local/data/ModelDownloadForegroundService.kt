package io.translatetube.local.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.translatetube.local.R
import io.translatetube.local.TranslateTubeApp
import io.translatetube.local.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class ExternalModelTask(
    val id: String,
    val title: String,
    val detail: String,
)

private object ExternalModelTaskRegistry {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    val tasks = MutableStateFlow<Map<String, ExternalModelTask>>(emptyMap())

    fun register(id: String, title: String): Boolean = synchronized(lock) {
        if (id in tasks.value) return@synchronized false
        tasks.value = tasks.value + (id to ExternalModelTask(id, title, "后台执行中；切换应用后继续"))
        true
    }

    fun attach(id: String, job: Job): Boolean = synchronized(lock) {
        if (id !in tasks.value) return@synchronized false
        jobs[id] = job
        true
    }

    fun cancel(id: String): Boolean = synchronized(lock) {
        val job = jobs[id] ?: return@synchronized false
        job.cancel()
        true
    }

    fun isActive(id: String): Boolean = tasks.value.containsKey(id)

    fun finish(id: String) = synchronized(lock) {
        jobs.remove(id)
        tasks.value = tasks.value - id
    }
}

private data class ModelNotificationContent(
    val title: String,
    val detail: String,
    val progress: Int? = null,
    val indeterminate: Boolean = false,
)

/**
 * Keeps model downloads and extraction alive when the settings screen/app goes to background.
 * A foreground notification is shown for the entire active download/extraction period.
 */
class ModelDownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TranslateTube:ModelDownload")
            .apply { setReferenceCounted(false) }
        runCatching { wakeLock?.acquire(6L * 60L * 60L * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelManager = (application as TranslateTubeApp).modelDownload
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> modelManager.togglePauseAll()
            ACTION_CANCEL_ACTIVE -> modelManager.cancelAll()
        }

        // Must enter foreground immediately after the service starts.
        startForegroundCompat(
            buildNotification(
                title = "正在准备后台任务",
                text = "正在准备下载或网页解析任务",
                ongoing = true,
                indeterminate = true,
            )
        )
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            var idleChecks = 0
            while (isActive) {
                delay(1000)
                val manager = (application as TranslateTubeApp).modelDownload
                val tts = manager.tts.value
                val asr = manager.asr.value
                val ttsActive = tts.status == ModelStatus.DOWNLOADING || tts.status == ModelStatus.EXTRACTING
                val asrActive = asr.status == ModelStatus.DOWNLOADING || asr.status == ModelStatus.EXTRACTING
                val managedActive = ttsActive || asrActive
                val piperTasks = io.translatetube.local.tts.PiperModels.downloadStates.value
                    .filterValues { it.isActive }
                val piperActive = piperTasks.isNotEmpty()
                val externalTasks = ExternalModelTaskRegistry.tasks.value.values.toList()
                val externalTasksActive = externalTasks.isNotEmpty()
                val externalTaskDetail = externalTasks.joinToString("；") { task ->
                    task.title + " · " + task.detail
                }.take(360)
                val active = managedActive || piperActive || externalTasksActive

                val baseContent = when {
                    ttsActive && asrActive -> ModelNotificationContent(
                        title = "正在准备模型",
                        detail = "配音：${statusText(tts)}；转写：${statusText(asr)}",
                        indeterminate = true,
                    )
                    ttsActive && tts.status == ModelStatus.DOWNLOADING -> ModelNotificationContent(
                        title = "正在下载配音模型",
                        detail = statusText(tts),
                        progress = tts.progress.coerceIn(0, 99).takeIf { tts.totalMB > 0L },
                        indeterminate = tts.totalMB <= 0L,
                    )
                    ttsActive && tts.status == ModelStatus.EXTRACTING -> ModelNotificationContent(
                        title = "正在安装配音模型",
                        detail = "解压安装 ${tts.progress.coerceIn(0, 99)}% · ${statusText(tts)}",
                        progress = tts.progress.coerceIn(0, 99),
                    )
                    ttsActive -> ModelNotificationContent(
                        title = "正在安装配音模型",
                        detail = statusText(tts),
                        indeterminate = true,
                    )
                    asrActive && asr.status == ModelStatus.DOWNLOADING -> ModelNotificationContent(
                        title = "正在下载转写模型",
                        detail = statusText(asr),
                        progress = asr.progress.coerceIn(0, 99).takeIf { asr.totalMB > 0L },
                        indeterminate = asr.totalMB <= 0L,
                    )
                    asrActive && asr.status == ModelStatus.EXTRACTING -> ModelNotificationContent(
                        title = "正在安装转写模型",
                        detail = "解压安装 ${asr.progress.coerceIn(0, 99)}% · ${statusText(asr)}",
                        progress = asr.progress.coerceIn(0, 99),
                    )
                    asrActive -> ModelNotificationContent(
                        title = "正在安装转写模型",
                        detail = statusText(asr),
                        indeterminate = true,
                    )
                    tts.status == ModelStatus.FAILED || asr.status == ModelStatus.FAILED -> {
                        val failures = buildList {
                            if (tts.status == ModelStatus.FAILED) add("配音模型：${statusText(tts)}")
                            if (asr.status == ModelStatus.FAILED) add("转写模型：${statusText(asr)}")
                        }
                        ModelNotificationContent(
                            title = "模型任务未完成",
                            detail = failures.joinToString("；"),
                        )
                    }
                    tts.status == ModelStatus.READY && asr.status == ModelStatus.READY -> ModelNotificationContent(
                        title = "模型已准备完成",
                        detail = "配音与语音转写模型均已就绪",
                    )
                    else -> ModelNotificationContent(
                        title = "模型任务已结束",
                        detail = "返回设置可查看模型状态",
                    )
                }

                val piperDetail = piperTasks.entries.joinToString("；") { (id, state) ->
                    val modelName = io.translatetube.local.tts.PiperModels.byId(id)?.voiceLabel ?: id
                    val progressText = if (
                        state.status == io.translatetube.local.tts.PiperModelDownloadStatus.EXTRACTING
                    ) state.stageMessage ?: "解压并安装中"
                    else "${state.progress.coerceIn(0, 99)}%"
                    "$modelName：$progressText"
                }
                val piperExtracting = piperTasks.values.any {
                    it.status == io.translatetube.local.tts.PiperModelDownloadStatus.EXTRACTING
                }
                val piperProgress = piperTasks.values
                    .filter { it.status == io.translatetube.local.tts.PiperModelDownloadStatus.DOWNLOADING }
                    .map { it.progress.coerceIn(0, 99) }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toInt()
                val content = when {
                    piperActive && managedActive -> baseContent.copy(
                        detail = listOf(baseContent.detail, piperDetail, externalTaskDetail)
                            .filter { it.isNotBlank() }.joinToString("；"),
                        progress = null,
                        indeterminate = true,
                    )
                    piperActive -> ModelNotificationContent(
                        title = "正在准备 Piper 配音模型",
                        detail = listOf(piperDetail, externalTaskDetail)
                            .filter { it.isNotBlank() }.joinToString("；"),
                        progress = piperProgress.takeUnless { piperExtracting },
                        indeterminate = piperExtracting || piperProgress == null || externalTasksActive,
                    )
                    managedActive && externalTasksActive -> baseContent.copy(
                        detail = listOf(baseContent.detail, externalTaskDetail)
                            .filter { it.isNotBlank() }.joinToString("；"),
                        progress = null,
                        indeterminate = true,
                    )
                    externalTasksActive -> ModelNotificationContent(
                        title = if (externalTasks.size == 1) externalTasks.first().title else "正在处理后台任务",
                        detail = externalTaskDetail,
                        indeterminate = true,
                    )
                    else -> baseContent
                }
                val paused = ModelPauseCoordinationPolicy.allActiveTasksPaused(
                    managedActive = managedActive,
                    managedPaused = manager.isPaused,
                    piperActive = piperActive,
                    piperPaused = io.translatetube.local.tts.PiperModels.isPaused,
                )
                val notification = buildNotification(
                    title = if (paused) "已暂停 · ${content.title}" else content.title,
                    text = if (paused) "已暂停 · ${content.detail}" else content.detail,
                    ongoing = active,
                    progress = content.progress,
                    indeterminate = content.indeterminate,
                    // Pause/cancel controls target the speech-model managers only. Do not show them
                    // for a tracked translation task that those actions cannot control.
                    showActions = active && !externalTasksActive,
                    paused = paused,
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)

                if (active) {
                    idleChecks = 0
                } else {
                    // Give the app-scoped download coroutine time to publish DOWNLOADING before stopping.
                    idleChecks++
                    if (idleChecks >= 5) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun statusText(state: ModelState): String = when (state.status) {
        ModelStatus.DOWNLOADING -> {
            if (state.totalMB > 0L) {
                "下载中 ${state.progress.coerceIn(0, 99)}% · ${state.downloadedMB}/${state.totalMB} MB"
            } else {
                "正在下载 · 已下载 ${state.downloadedMB} MB（文件总大小未知）"
            }
        }
        ModelStatus.EXTRACTING -> state.stageMessage
            ?.takeIf { it.isNotBlank() }
            ?.removePrefix("正在")
            ?: "解压并安装中"
        ModelStatus.READY -> "已完成"
        ModelStatus.FAILED -> state.error?.take(90) ?: "下载失败"
        ModelStatus.NOT_DOWNLOADED -> "等待中"
    }

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean = true,
        progress: Int? = null,
        indeterminate: Boolean = false,
        showActions: Boolean = false,
        paused: Boolean = false,
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_brand)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    7302,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .apply {
                if (showActions) {
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val toggleIntent = Intent(this@ModelDownloadForegroundService, ModelDownloadForegroundService::class.java)
                        .setAction(ACTION_TOGGLE_PAUSE)
                        .setData(Uri.parse("translate-tube://models/pause"))
                    val togglePending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(this@ModelDownloadForegroundService, 7303, toggleIntent, flags)
                    } else {
                        PendingIntent.getService(this@ModelDownloadForegroundService, 7303, toggleIntent, flags)
                    }
                    addAction(
                        if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                        if (paused) "继续" else "暂停",
                        togglePending,
                    )

                    val cancelIntent = Intent(this@ModelDownloadForegroundService, ModelDownloadForegroundService::class.java)
                        .setAction(ACTION_CANCEL_ACTIVE)
                        .setData(Uri.parse("translate-tube://models/cancel"))
                    val cancelPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(this@ModelDownloadForegroundService, 7304, cancelIntent, flags)
                    } else {
                        PendingIntent.getService(this@ModelDownloadForegroundService, 7304, cancelIntent, flags)
                    }
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPending)
                }
                if (progress != null) {
                    setProgress(100, progress.coerceIn(0, 100), false)
                } else {
                    // Clear a previous determinate bar; animate only while a real extraction/install is active.
                    setProgress(0, 0, indeterminate)
                }
            }
            .build()

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台下载与处理",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示模型下载、安装与网页解析任务状态"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TOGGLE_PAUSE = "io.translatetube.local.action.TOGGLE_MODEL_DOWNLOAD_PAUSE"
        const val ACTION_CANCEL_ACTIVE = "io.translatetube.local.action.CANCEL_MODEL_DOWNLOAD"
        const val ACTION_EXTERNAL_TASK_CHANGED = "io.translatetube.local.action.EXTERNAL_MODEL_TASK_CHANGED"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 7302

        /**
         * Run a long model download in Application scope and keep a foreground notification alive.
         * The stable task ID prevents duplicate downloads after navigating away and back.
         */
        fun launchExternalTask(
            context: Context,
            taskId: String,
            title: String,
            scope: CoroutineScope,
            block: suspend () -> Unit,
        ): Job? {
            require(taskId.isNotBlank()) { "taskId must not be blank" }
            val appContext = context.applicationContext
            if (!ExternalModelTaskRegistry.register(taskId, title)) return null

            try {
                val intent = Intent(appContext, ModelDownloadForegroundService::class.java)
                    .setAction(ACTION_EXTERNAL_TASK_CHANGED)
                    .setData(Uri.parse("translate-tube://model-download/external/" + Uri.encode(taskId)))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (failure: Exception) {
                ExternalModelTaskRegistry.finish(taskId)
                throw failure
            }

            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    ExternalModelTaskRegistry.finish(taskId)
                }
            }
            if (!ExternalModelTaskRegistry.attach(taskId, job)) {
                job.cancel()
                ExternalModelTaskRegistry.finish(taskId)
                return null
            }
            job.start()
            return job
        }

        fun cancelExternalTask(taskId: String): Boolean =
            ExternalModelTaskRegistry.cancel(taskId)

        fun isExternalTaskActive(taskId: String): Boolean =
            ExternalModelTaskRegistry.isActive(taskId)
    }
}
