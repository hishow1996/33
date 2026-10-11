package io.translatetube.local

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TranslateTubeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 模型下载单例（方案 B：配音/转写模型软件内下载） */
    val modelDownload by lazy { io.translatetube.local.data.ModelDownloadManager(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        prefetchTranslateModels()
    }

    /**
     * 首启静默预下载 ML Kit 离线翻译模型（en/zh 必备 + 常用源语言）。
     * 下载一次后永久离线；失败静默（首次翻译时会再试）。
     */
    private fun prefetchTranslateModels() {
        appScope.launch {
            delay(2500) // 避开冷启动高峰
            val langs = listOf("en", "zh", "ja", "ko", "es", "fr", "de", "ru")
            for (l in langs) {
                try {
                    // 与播放页翻译共用下载协调器；模型已经存在时不再请求下载包。
                    io.translatetube.local.translate.MlKitModelCoordinator.ensureDownloaded(l)
                } catch (e: Exception) {
                    Log.w("TT", "翻译模型预下载失败: $l ${e.message}")
                }
            }
            Log.i("TT", "翻译模型预下载流程完成")
        }
    }
}
