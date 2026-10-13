package io.translatetube.local.yt

import java.net.URI

/** Pure URL parsing for common YouTube watch, Shorts, share, live, and embed links. */
internal object YouTubeUrlParser {
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val pathPattern = Regex("(?:^|/)(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})(?:/|$)", RegexOption.IGNORE_CASE)

    fun extractVideoId(text: String): String? {
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
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val acceptedHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
        if (!acceptedHost || uri.userInfo != null) return null

        if (host == "youtu.be") {
            val pathId = uri.path.orEmpty().trim('/').substringBefore('/')
            return pathId.takeIf(idPattern::matches)
        }

        val query = uri.rawQuery.orEmpty()
        for (parameter in query.split('&')) {
            if (parameter.substringBefore('=') == "v") {
                val value = parameter.substringAfter('=', "")
                if (idPattern.matches(value)) return value
            }
        }

        val pathMatch = pathPattern.find(uri.path.orEmpty()) ?: return null
        return pathMatch.groupValues[1].takeIf(idPattern::matches)
    }
}
