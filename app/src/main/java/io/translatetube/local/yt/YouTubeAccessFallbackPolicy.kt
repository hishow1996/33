package io.translatetube.local.yt

import java.util.Locale

/**
 * Identifies the specific NewPipe anonymous-watch denial for which switching to the
 * app's existing yt-dlp resolver is a useful recovery attempt. This does not claim to
 * bypass YouTube's access checks; yt-dlp may be denied by the same IP restriction.
 */
internal object YouTubeAccessFallbackPolicy {
    private val anonymousAccessMarkers = listOf(
        "login_required",
        "sign in to confirm that you're not a bot",
        "sign in to confirm you're not a bot",
        "temporarily blocked anonymous watch access",
        "confirm you're not a bot",
    )

    fun shouldTryYtDlp(error: Throwable): Boolean {
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        repeat(8) {
            val cause = current ?: return false
            if (!visited.add(cause)) return false
            val message = cause.message.orEmpty().lowercase(Locale.ROOT)
            if (anonymousAccessMarkers.any(message::contains)) return true
            current = cause.cause
        }
        return false
    }
}
