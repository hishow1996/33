package io.translatetube.local.web

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Converts Bilibili's app-only video deep links into safe HTTPS video pages.
 * Only canonical video IDs from bilibili.com subdomains or the known video route
 * are accepted. Tracking parameters are discarded; the selected page index is preserved.
 */
internal object BilibiliDeepLinkPolicy {
    private val videoPathPattern = Regex(
        """(?:^|/)video/(BV[A-Za-z0-9]{10}|av[0-9]+)(?:/|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val bvidPattern = Regex("""BV[A-Za-z0-9]{10}""")

    private data class VideoPage(
        val videoId: String,
        val page: Int?,
    )

    /**
     * 是否为 B 站视频页地址：http(s) 的 bilibili.com 系域名 /video/BV|av 路径，
     * 或 bilibili:// 客户端视频深链。用于 WebView 导航拦截（页内点开 B 站视频
     * 走原生直连解析，不让 m 站页面接管——其播放器在 WebView 内渲染不出来）。
     */
    fun isVideoPageUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("bilibili://", ignoreCase = true) ||
            trimmed.startsWith("bili://", ignoreCase = true)
        ) {
            return toWebUrl(trimmed) != null
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host != "bilibili.com" && !host.endsWith(".bilibili.com")) return false
        return videoPathPattern.containsMatchIn(uri.path.orEmpty())
    }

    fun toWebUrl(rawUrl: String): String? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("bilibili", "bili")) return null

        val parameters = decodeQuery(uri.rawQuery)
        // Official Bilibili share intents commonly carry the real HTTPS page inside
        // open_app_url; prefer that ID over the shorter numeric aid in the outer URI.
        for (key in listOf("open_app_url", "url", "target")) {
            val nestedUrl = parameters[key] ?: continue
            val nestedPage = parseBilibiliWebVideo(nestedUrl) ?: continue
            return buildWebUrl(nestedPage)
        }

        val routeId = videoPathPattern.find(uri.path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeVideoId)
        val hostVideoId = if (uri.host.equals("video", ignoreCase = true)) {
            normalizeVideoId(uri.path.orEmpty().trim('/').substringBefore('/'))
        } else {
            null
        }
        val queryVideoId = normalizeVideoId(
            parameters["aid"] ?: parameters["avid"] ?: parameters["video_id"],
        )
        val videoId = routeId ?: hostVideoId ?: queryVideoId ?: return null
        val page = parameters["p"]?.toIntOrNull()?.takeIf { it in 1..99999 }
            ?: parameters["page"]?.toIntOrNull()?.let { (it + 1).takeIf { value -> value in 1..99999 } }
        return buildWebUrl(VideoPage(videoId, page))
    }

    private fun parseBilibiliWebVideo(rawUrl: String): VideoPage? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || uri.userInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "bilibili.com" && !host.endsWith(".bilibili.com")) return null

        val videoId = videoPathPattern.find(uri.path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeVideoId)
            ?: return null
        val page = decodeQuery(uri.rawQuery)["p"]
            ?.toIntOrNull()
            ?.takeIf { it in 1..99999 }
        return VideoPage(videoId, page)
    }

    private fun normalizeVideoId(rawValue: String?): String? {
        val value = rawValue?.trim()?.trim('/') ?: return null
        if (bvidPattern.matches(value)) return value
        if (value.matches(Regex("""(?i)av[0-9]+"""))) return "av" + value.substring(2)
        if (value.matches(Regex("""[0-9]+"""))) return "av$value"
        return null
    }

    private fun buildWebUrl(page: VideoPage): String = buildString {
        append("https://m.bilibili.com/video/")
        append(page.videoId)
        page.page?.takeIf { it > 1 }?.let {
            append("?p=")
            append(it)
        }
    }

    private fun decodeQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            val key = runCatching {
                URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name())
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (result.containsKey(key)) return@forEach
            val value = runCatching {
                URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
            }.getOrNull() ?: return@forEach
            result[key] = value
        }
        return result
    }
}
