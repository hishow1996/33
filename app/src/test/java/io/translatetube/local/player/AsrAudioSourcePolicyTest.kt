package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioSourcePolicyTest {
    @Test
    fun localContentAndFileUrisAlwaysUseMediaExtractor() {
        // Local document URIs must never be sent to the network downloader.
        listOf("content", "file").forEach { scheme ->
            assertEquals(
                scheme,
                AsrAudioSourcePolicy.Mode.MEDIA_EXTRACTOR,
                AsrAudioSourcePolicy.choose(
                    hasYouTubeVideoId = false,
                    sourceScheme = scheme,
                    shouldCacheRemoteAudio = true,
                ),
            )
        }
    }

    @Test
    fun progressiveRemoteVideoUsesMediaExtractorButDirectAudioAndManifestsUseCache() {
        assertEquals(
            AsrAudioSourcePolicy.Mode.MEDIA_EXTRACTOR,
            AsrAudioSourcePolicy.choose(false, sourceScheme = "https", shouldCacheRemoteAudio = false),
        )
        assertEquals(
            AsrAudioSourcePolicy.Mode.REMOTE_AUDIO_CACHE,
            AsrAudioSourcePolicy.choose(false, sourceScheme = "https", shouldCacheRemoteAudio = true),
        )
    }

    @Test
    fun youtubeAudioDownloadTakesPrecedenceOverGenericRemoteRouting() {
        assertEquals(
            AsrAudioSourcePolicy.Mode.YOUTUBE_AUDIO_CACHE,
            AsrAudioSourcePolicy.choose(
                hasYouTubeVideoId = true,
                sourceScheme = "https",
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
