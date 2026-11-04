package io.translatetube.local.yt

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Pure URL parsing for common YouTube watch, Shorts, share, live, and embed links. */
internal object YouTubeUrlParser {
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val pathPattern = Regex("(?:^|/)(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})(?:/|$)", RegexOption.IGNORE_CASE)

    fun extractVideoId(text: String): String? {
        // Reject hidden controls before trimming; otherwise a newline-wrapped ID/URL is accepted.
        if (text.any { Character.isISOControl(it) }) return null
        val input = text.trim()
        if (idPattern.matches(input)) return input
        if (input.isEmpty()) return null

        val normalized = when {
            input.startsWith("//") -> "https:$input"
            input.startsWith("https://", ignoreCase = true) ||
                input.startsWith("http://", ignoreCase = true) -> input
            else -> "https://$input"
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        val acceptedHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
        val port = uri.port
        if (!acceptedHost || uri.userInfo != null || (port != -1 && port !in 1..65535)) return null

        if (host == "youtu.be") {
            val pathId = uri.path.orEmpty().trim('/').substringBefore('/')
            return pathId.takeIf(idPattern::matches)
        }

        val query = uri.rawQuery.orEmpty()
        for (parameter in query.split('&')) {
            val name = runCatching {
                URLDecoder.decode(parameter.substringBefore('='), Charsets.UTF_8.name())
            }.getOrNull() ?: continue
            if (name == "v") {
                val value = runCatching {
                    URLDecoder.decode(parameter.substringAfter('=', ""), Charsets.UTF_8.name())
                }.getOrNull() ?: continue
                if (idPattern.matches(value)) return value
            }
        }

        val pathMatch = pathPattern.find(uri.path.orEmpty()) ?: return null
        return pathMatch.groupValues[1].takeIf(idPattern::matches)
    }
}
