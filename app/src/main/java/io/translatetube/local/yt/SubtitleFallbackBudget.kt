package io.translatetube.local.yt

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A subtitle request that came back with a non-200 status. Keeps the code machine-readable. */
internal class SubtitleHttpException(val code: Int) :
    IllegalStateException("字幕下载需要完整 HTTP 200 响应，收到 HTTP $code")

/**
 * Decides when to stop walking through the remaining caption tracks.
 *
 * The caller tries every caption track in preference order and each try can wait up to the
 * HTTP timeout. A video can have dozens of tracks, so with the network down the parsing page
 * used to sit on "正在下载并读取字幕文件" for minutes before reaching the audio-transcription
 * fallback. Stopping early loses nothing: the remaining tracks are the lowest priority ones, and
 * the audio fallback is what runs when no caption works anyway.
 */
internal class SubtitleFallbackBudget(
    private val maxConnectivityFailures: Int = 2,
    private val maxConsecutiveFailures: Int = 8,
) {
    private var connectivityFailures = 0
    private var consecutiveFailures = 0

    fun recordSuccess() {
        connectivityFailures = 0
        consecutiveFailures = 0
    }

    /**
     * Record that one whole track failed ([error] null when it answered but had no usable
     * cues). Returns true when the caller should give up on the remaining tracks.
     */
    fun recordTrackFailure(error: Throwable?): Boolean {
        consecutiveFailures += 1
        if (error != null && isRateLimited(error)) return true
        if (error != null && isConnectivityFailure(error)) {
            connectivityFailures += 1
        } else {
            connectivityFailures = 0
        }
        return connectivityFailures >= maxConnectivityFailures ||
            consecutiveFailures >= maxConsecutiveFailures
    }

    companion object {
        /** The network itself is the problem; asking for the same host again will not help. */
        fun isConnectivityFailure(error: Throwable): Boolean =
            error is UnknownHostException || error is ConnectException ||
                error is NoRouteToHostException || error is SocketTimeoutException ||
                error is SSLException ||
                // OkHttp reports a cut connection as a plain IOException with these texts.
                (error is IOException && (
                    error.message.orEmpty().contains("unexpected end of stream", ignoreCase = true) ||
                        error.message.orEmpty().contains("connection reset", ignoreCase = true) ||
                        error.message.orEmpty().contains("timeout", ignoreCase = true)
                    ))

        /** HTTP 429: the server asked us to slow down; the other tracks would get the same answer. */
        fun isRateLimited(error: Throwable): Boolean =
            error is SubtitleHttpException && error.code == 429
    }
}
