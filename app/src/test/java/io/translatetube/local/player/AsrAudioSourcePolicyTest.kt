package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioSourcePolicyTest {
    @Test
    fun localContentAndFileUrisAlwaysUseMediaExtractor() {
        // URI details are resolved by Android's Uri at the call site. Neither local URI scheme is
        // an HTTP source, so it must go straight to MediaExtractor rather than network downloader.
        assertEquals(
            AsrAudioSourcePolicy.Mode.MEDIA_EXTRACTOR,
            AsrAudioSourcePolicy.choose(
                hasYouTubeVideoId = false,
                isHttpSource = false,
                shouldCacheRemoteAudio = false,
            ),
        )
        assertEquals(
            AsrAudioSourcePolicy.Mode.MEDIA_EXTRACTOR,
            AsrAudioSourcePolicy.choose(
                hasYouTubeVideoId = false,
                isHttpSource = false,
                shouldCacheRemoteAudio = true,
            ),
        )
    }

    @Test
    fun progressiveRemoteVideoUsesMediaExtractorButDirectAudioAndManifestsUseCache() {
        assertEquals(
            AsrAudioSourcePolicy.Mode.MEDIA_EXTRACTOR,
            AsrAudioSourcePolicy.choose(false, isHttpSource = true, shouldCacheRemoteAudio = false),
        )
        assertEquals(
            AsrAudioSourcePolicy.Mode.REMOTE_AUDIO_CACHE,
            AsrAudioSourcePolicy.choose(false, isHttpSource = true, shouldCacheRemoteAudio = true),
        )
    }

    @Test
    fun youtubeAudioDownloadTakesPrecedenceOverGenericRemoteRouting() {
        assertEquals(
            AsrAudioSourcePolicy.Mode.YOUTUBE_AUDIO_CACHE,
            AsrAudioSourcePolicy.choose(
                hasYouTubeVideoId = true,
                isHttpSource = true,
                shouldCacheRemoteAudio = false,
            ),
        )
    }

    @Test
    fun localFlowLabelsExtractionAndExplainsWhisperModelRequirement() {
        assertTrue(AsrAudioSourcePolicy.localExtractionProgress().contains("本地视频提取音轨"))
        assertTrue(AsrAudioSourcePolicy.localSubtitleStatus().contains("中文字幕"))
        assertTrue(AsrAudioSourcePolicy.missingLocalAsrModelHint().contains("转写模型"))
        assertTrue(AsrAudioSourcePolicy.missingLocalAsrModelHint().contains("中文配音"))
    }
}
