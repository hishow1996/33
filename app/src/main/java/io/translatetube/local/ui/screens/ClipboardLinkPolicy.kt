package io.translatetube.local.ui.screens

import io.translatetube.local.web.SharedLinkTextParser
import io.translatetube.local.web.WebMediaUrlPolicy
import io.translatetube.local.yt.YouTubeUrlParser

/**
 * 首页打开时，判断剪贴板里的文字是不是值得提示"粘贴"的视频链接。
 * 只做纯逻辑判断（不依赖 Android），读剪贴板本身在界面层完成。
 */
internal object ClipboardLinkPolicy {
    /** 剪贴板文字过长多半是一大段正文，不当作链接提示。 */
    const val MAX_CLIP_CHARS = 2_000
    private const val MAX_LABEL_CHARS = 48

    /**
     * 返回可以直接填入地址栏的链接；不是链接、不是能处理的地址、或用户已经处理过这条就返回 null。
     * [handledUrl] 是用户上次已粘贴、已忽略或已提交的那条链接。
     */
    fun candidate(clipText: String?, handledUrl: String?): String? {
        val text = clipText?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_CLIP_CHARS) return null
        val url = SharedLinkTextParser.extractFirstHttpUrl(text) ?: return null
        if (url == handledUrl) return null
        val usable = YouTubeUrlParser.extractVideoId(url) != null ||
            WebMediaUrlPolicy.normalizeDirectMediaUrl(url) != null ||
            WebMediaUrlPolicy.normalizePageUrl(url) != null
        return url.takeIf { usable }
    }

    /** 链接的类型，用来在提示条上告诉用户识别成了什么。 */
    enum class Kind(val title: String) {
        YOUTUBE("YouTube 视频"),
        DIRECT_MEDIA("视频直链"),
        WEB_PAGE("网页视频"),
    }

    /** 与首页提交时的判断顺序一致：先看 YouTube，再看直链，其余都按网页视频处理。 */
    fun kind(url: String): Kind = when {
        YouTubeUrlParser.extractVideoId(url) != null -> Kind.YOUTUBE
        WebMediaUrlPolicy.normalizeDirectMediaUrl(url) != null -> Kind.DIRECT_MEDIA
        else -> Kind.WEB_PAGE
    }

    /** 提示条里显示的简短链接：去掉协议和 www.，过长就截断。 */
    fun label(url: String): String {
        val trimmed = url.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        return if (trimmed.length <= MAX_LABEL_CHARS) trimmed else trimmed.take(MAX_LABEL_CHARS - 1) + "…"
    }
}
