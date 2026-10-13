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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.translatetube.local.R
import io.translatetube.local.TranslateTubeApp
import io.translatetube.local.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        // Must enter foreground immediately after the service starts.
        startForegroundCompat(
            buildNotification(
                title = "正在准备模型任务",
                text = "正在检查配音与语音转写模型",
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
                val active = ttsActive || asrActive

                val content = when {
                    ttsActive && asrActive -> ModelNotificationContent(
                        title = "正在准备模型",
                        detail = "配音：${statusText(tts)}；转写：${statusText(asr)}",
                        indeterminate = true,
                    )
                    ttsActive && tts.status == ModelStatus.DOWNLOADING -> ModelNotificationContent(
                        title = "正在下载配音模型",
                        detail = statusText(tts),
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
                val notification = buildNotification(
                    title = content.title,
                    text = content.detail,
                    ongoing = active,
                    progress = content.progress,
                    indeterminate = content.indeterminate,
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
            val size = if (state.totalMB > 0) " · ${state.downloadedMB}/${state.totalMB} MB" else ""
            "下载中 ${state.progress.coerceIn(0, 99)}%$size"
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
                "模型下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示配音与语音转写模型的下载、校验和安装状态"
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
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 7302
    }
}
