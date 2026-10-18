package io.translatetube.local

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.translatetube.local.util.CrashGuard

class TranslateTubeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        CrashGuard.install(this)
        super.onCreate()
        // Image loading is initialized lazily by the screens that need it.
        // Avoid building Coil's HTTP/cache/component pipeline on the cold-start main thread.
        // yt-dlp 规则版本检测：上午/下午/晚上各一次（时段内去重，深夜静默），
        // 仅几 KB 流量；网络失败静默，不打扰启动。
        appScope.launch {
            runCatching { io.translatetube.local.yt.YtDlpRepo.checkForUpdate(this@TranslateTubeApp) }
        }
    }

    /** Model downloads run only when the user uses the relevant feature; no bulk ZIP prefetch at startup. */
    val modelDownload by lazy {
        io.translatetube.local.data.ModelDownloadManager(this, appScope)
    }
}
