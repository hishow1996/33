package io.translatetube.local.player

/** Chooses how ASR obtains audio without ever downloading a local video as a network resource. */
internal object AsrAudioSourcePolicy {
    enum class Mode {
        /** YouTube extractor provides a dedicated, bounded/resumable audio download. */
        YOUTUBE_AUDIO_CACHE,

        /** Direct audio and supported clear HLS/DASH VOD are materialized through segment caching. */
        REMOTE_AUDIO_CACHE,

        /** Local content/file URIs and accessible progressive remote video use MediaExtractor. */
        MEDIA_EXTRACTOR,
    }

    fun choose(
        hasYouTubeVideoId: Boolean,
        isHttpSource: Boolean,
        shouldCacheRemoteAudio: Boolean,
    ): Mode = when {
        hasYouTubeVideoId -> Mode.YOUTUBE_AUDIO_CACHE
        isHttpSource && shouldCacheRemoteAudio -> Mode.REMOTE_AUDIO_CACHE
        else -> Mode.MEDIA_EXTRACTOR
    }

    fun localExtractionProgress(): String =
        "正在从本地视频提取音轨并转换为 16 kHz 单声道 PCM…"

    fun localSubtitleStatus(): String =
        "本地视频没有可复用的字幕缓存；正在提取音轨识别人声，随后生成中文字幕与中文配音。"

    fun missingLocalAsrModelHint(): String =
        "本地视频还没有可用的中文字幕。下载「转写模型」后，大象会提取视频音轨、识别语音、翻译并生成中文配音（设置 → 语音模型）。"
}
