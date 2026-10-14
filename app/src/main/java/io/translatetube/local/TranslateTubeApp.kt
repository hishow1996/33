package io.translatetube.local

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TranslateTubeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Register SVG decoding once for the shared Coil loader. This is required by the
        // provider-logo cache as well as the bundled, offline Google Translate vector icon.
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                .components {
                    add(coil.decode.SvgDecoder.Factory())
                }
                .build()
        )
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
