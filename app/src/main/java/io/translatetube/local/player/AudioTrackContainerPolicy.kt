package io.translatetube.local.player

import java.util.Locale

/** Only map codecs to containers that Android's media muxer explicitly supports. */
internal object AudioTrackContainerPolicy {
    enum class Container { MP4, WEBM }

    fun choose(mime: String): Container? = when (mime.trim().lowercase(Locale.ROOT)) {
        // AAC, AMR-NB, and AMR-WB are valid audio tracks for the MP4 muxer.
        "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb" -> Container.MP4
        // Vorbis and Opus are the expected audio codecs in WebM media.
        "audio/vorbis", "audio/opus" -> Container.WEBM
        else -> null
    }
}
