package io.translatetube.local

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.translatetube.local.util.CrashGuard

class TranslateTubeApp : Application() {
    companion object {
        /** 进程级 Application 引用：供无 Context 参数的工具（如翻译 WebView 宿主）使用。 */
        @Volatile
        lateinit var instance: TranslateTubeApp
            private set
    }

    /** 当前（或最近一个存活）的 Activity：给 WebView 这类必须有渲染表面的组件当宿主。 */
    @Volatile
    var currentActivity: android.app.Activity? = null
        private set

    private val lifecycleTracker = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) {
            currentActivity = activity
        }

        override fun onActivityDestroyed(activity: android.app.Activity) {
            if (currentActivity === activity) currentActivity = null
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityStarted(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivityStopped(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        instance = this
        registerActivityLifecycleCallbacks(lifecycleTracker)
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
