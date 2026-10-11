package io.translatetube.local

import android.app.Application
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
                    val tag = TranslateLanguage.fromLanguageTag(l) ?: continue
                    RemoteModelManager.getInstance()
                        .download(TranslateRemoteModel.Builder(tag).build(), DownloadConditions.Builder().build())
                        .addOnFailureListener { Log.w("TT", "翻译模型预下载失败: $l ${it.message}") }
                        .await() // 串行逐个下载，避免并发被限
                } catch (_: Exception) {
                }
            }
            Log.i("TT", "翻译模型预下载流程完成")
        }
    }
}
