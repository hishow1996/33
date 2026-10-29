package io.translatetube.local

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.translatetube.local.util.CrashGuard
import io.translatetube.local.data.AppDatabase
import io.translatetube.local.translate.Crypto
import io.translatetube.local.tts.GeminiTtsVoices
import kotlinx.coroutines.flow.firstOrNull

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

        // 旧版本将 Gemini 翻译密钥同时用于配音；升级时只迁移一次，之后两项配置独立保存。
        val ttsKeyPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!ttsKeyPrefs.getBoolean(GeminiTtsVoices.PREF_API_KEY_MIGRATION_DONE, false)) {
            val currentTtsKey = ttsKeyPrefs.getString(GeminiTtsVoices.PREF_API_KEY, "").orEmpty()
            val legacyFreeKey = ttsKeyPrefs.getString("free_gemini_key", "").orEmpty()
            if (currentTtsKey.isNotBlank()) {
                ttsKeyPrefs.edit()
                    .putBoolean(GeminiTtsVoices.PREF_API_KEY_MIGRATION_DONE, true)
                    .apply()
            } else if (legacyFreeKey.isNotBlank()) {
                val legacyPlainKey = Crypto.decrypt(legacyFreeKey).trim()
                val editor = ttsKeyPrefs.edit()
                if (legacyPlainKey.isNotBlank()) {
                    editor.putString(GeminiTtsVoices.PREF_API_KEY, Crypto.encrypt(legacyPlainKey))
                }
                editor.putBoolean(GeminiTtsVoices.PREF_API_KEY_MIGRATION_DONE, true).apply()
            } else {
                // 旧版本也允许复用 API 管理中的 Gemini 配置；仅在升级迁移时复制一次。
                appScope.launch {
                    val migrationCompleted = runCatching {
                        val legacyProfileKey = AppDatabase.get(this@TranslateTubeApp)
                            .apiProfileDao()
                            .enabled()
                            .firstOrNull {
                                it.type.equals("GEMINI", ignoreCase = true) && it.apiKey.isNotBlank()
                            }
                            ?.apiKey
                            .orEmpty()
                        val legacyPlainKey = Crypto.decrypt(legacyProfileKey).trim()
                        if (
                            legacyPlainKey.isNotBlank() &&
                            ttsKeyPrefs.getString(GeminiTtsVoices.PREF_API_KEY, "").isNullOrBlank()
                        ) {
                            ttsKeyPrefs.edit()
                                .putString(GeminiTtsVoices.PREF_API_KEY, Crypto.encrypt(legacyPlainKey))
                                .apply()
                        }
                        true
                    }.getOrDefault(false)
                    if (migrationCompleted) {
                        ttsKeyPrefs.edit()
                            .putBoolean(GeminiTtsVoices.PREF_API_KEY_MIGRATION_DONE, true)
                            .apply()
                    }
                }
            }
        }

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
