package io.translatetube.local.ui.screens

/** Audio-source categories displayed alongside manually selectable webpage candidates. */
internal enum class WebCandidateAudioHintKind {
    DETECTING,
    INDEPENDENT,
    LOW_BITRATE_MUXED,

    /** A direct video file whose header shows an audio track muxed with the picture. */
    MUXED_DIRECT,

    /** The file header shows a picture but no audio track, so there is nothing to transcribe. */
    NO_AUDIO,
    UNKNOWN,
    FAILED,
}

/** Audio-extraction information for one manually selectable webpage media candidate. */
internal data class WebCandidateAudioHint(
    val message: String,
    val kind: WebCandidateAudioHintKind = WebCandidateAudioHintKind.UNKNOWN,
    /** Rendition selected inside an inspected HLS master; it may also appear as another list item. */
    val renditionUrl: String? = null,
    /** Original detection exception; used to write the real stack trace to local diagnostic logs. */
    val failure: Throwable? = null,
    /** Safe, structured request/response evidence for the in-app diagnostics page. */
    val diagnosticContext: Map<String, String> = emptyMap(),
    /** Audio track type and video bitrate details, when the check could read them. */
    val mediaInfo: WebCandidateMediaInfo? = null,
)

/** Builds the one-line hints (and their structured diagnostics) from detected track details. */
internal object WebCandidateAudioHints {

    fun forDirectMp4(info: WebCandidateMediaInfo): WebCandidateAudioHint {
        val audio = info.audioSummary()
        val video = info.videoSummary()
        val duration = info.durationSeconds?.takeIf { it > 0.0 }?.let { WebMediaFormat.duration(it) }
        val diagnostics = linkedMapOf("检测步骤" to "直链 ${info.container ?: "MP4"} 文件头音轨检测")
            .apply { putAll(info.diagnostics()) }

        val (kind, message) = when {
            info.hasVideo == true && info.hasAudio == true -> {
                WebCandidateAudioHintKind.MUXED_DIRECT to buildString {
                    append("音画混合直链")
                    append(" · 音轨 ").append(audio ?: "类型未知")
                    if (info.audioTrackCount > 1) append(" · 共 ${info.audioTrackCount} 条音轨")
                    if (video != null) append(" · 视频 ").append(video)
                    if (duration != null) append(" · ").append(duration)
                }
            }
            info.hasVideo == true && info.hasAudio == false -> {
                WebCandidateAudioHintKind.NO_AUDIO to buildString {
                    append("没有音轨 · 无法提取音频转写")
                    if (video != null) append(" · 视频 ").append(video)
                }
            }
            info.hasVideo == false && info.hasAudio == true -> {
                WebCandidateAudioHintKind.INDEPENDENT to buildString {
                    append("独立音轨 · 纯音频文件")
                    if (audio != null) append(" · ").append(audio)
                    if (duration != null) append(" · ").append(duration)
                }
            }
            else -> {
                WebCandidateAudioHintKind.UNKNOWN to buildString {
                    append("直链视频 · 音轨类型待确认")
                    if (video != null) append(" · 视频 ").append(video)
                }
            }
        }
        return WebCandidateAudioHint(
            message = message,
            kind = kind,
            diagnosticContext = diagnostics,
            mediaInfo = info,
        )
    }

    fun forHls(
        description: HlsTrackDescription,
        independentAudio: Boolean,
        renditionUrl: String,
    ): WebCandidateAudioHint {
        val info = description.info
        val audio = info.audioSummary()
        val video = info.videoSummary()
        val diagnostics = info.diagnostics()

        if (independentAudio || description.audioOnlyVariant) {
            val message = buildString {
                append(if (independentAudio) "检测到独立音轨" else "检测到独立音轨（音频专用档）")
                if (audio != null) append(' ').append(audio)
                append(" · 不需要下载视频画面")
                if (info.audioTrackCount > 1) append(" · 共 ${info.audioTrackCount} 条音轨")
                if (video != null) append(" · 视频 ").append(video)
            }
            return WebCandidateAudioHint(
                message = message,
                kind = WebCandidateAudioHintKind.INDEPENDENT,
                renditionUrl = renditionUrl,
                diagnosticContext = diagnostics,
                mediaInfo = info,
            )
        }

        val message = buildString {
            append("混合音视频 · 已选择低码率版本")
            append(" · ").append(video ?: "清晰度/码率未知")
            if (audio != null) append(" · 音轨 ").append(audio)
            if (info.videoVariantCount > 1) append(" · 共 ${info.videoVariantCount} 档画质")
        }
        return WebCandidateAudioHint(
            message = message,
            kind = WebCandidateAudioHintKind.LOW_BITRATE_MUXED,
            renditionUrl = renditionUrl,
            diagnosticContext = diagnostics,
            mediaInfo = info,
        )
    }
}
