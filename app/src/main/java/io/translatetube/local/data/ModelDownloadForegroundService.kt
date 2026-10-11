package io.translatetube.local.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.translatetube.local.TranslateTubeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        startForegroundCompat(buildNotification("正在准备模型下载…"))
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

                val message = when {
                    ttsActive && asrActive -> "配音模型 ${statusText(tts)}；转写模型 ${statusText(asr)}"
                    ttsActive -> "配音模型：${statusText(tts)}"
                    asrActive -> "转写模型：${statusText(asr)}"
                    else -> "模型下载任务已结束"
                }
                val notification = buildNotification(message, ongoing = active)
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
        ModelStatus.DOWNLOADING -> "下载 ${state.progress}%"
        ModelStatus.EXTRACTING -> "解压安装"
        ModelStatus.READY -> "已完成"
        ModelStatus.FAILED -> state.error?.take(45) ?: "失败"
        ModelStatus.NOT_DOWNLOADED -> "等待中"
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("大象翻译 · 模型下载")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, ongoing)
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
                description = "显示配音和语音转写模型的后台下载、解压进度"
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
