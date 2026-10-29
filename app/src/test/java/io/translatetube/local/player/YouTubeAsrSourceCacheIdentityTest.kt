package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAsrSourceCacheIdentityTest {
    @Test
    fun activeAsrSourceKeyDoesNotEncodeCaptionModeOrCaptionCacheVersion() {
        val stableKey = YouTubeAsrSourceCacheIdentity.key(
            videoKey = "youtube:abc123",
            asrCacheIdentity = "asr-provider-a-config-a",
        )

        assertFalse(stableKey.contains("subtitle-english"))
        assertFalse(stableKey.contains("subtitle-original"))
        assertEquals(
            "youtube:abc123|source|youtube-asr-v1|asr-asr-provider-a-config-a",
            stableKey,
        )
        assertNotEquals(
            "youtube:abc123|source|youtube-asr-v9|asr-asr-provider-a-config-a",
            stableKey,
        )
    }

    @Test
    fun changingAsrIdentityCreatesAnIndependentTranscriptCache() {
        assertNotEquals(
            YouTubeAsrSourceCacheIdentity.key("youtube:abc123", "asr-a"),
            YouTubeAsrSourceCacheIdentity.key("youtube:abc123", "asr-b"),
        )
    }

    @Test
    fun legacyKeysCoverPriorStableVersionAndBothFormerCaptionModes() {
        assertEquals(
            listOf(
                "youtube:abc123|source|youtube-asr-v9|asr-a",
                "youtube:abc123|source|youtube-v9-subtitle-english|asr-a",
                "youtube:abc123|source|youtube-v9-subtitle-original|asr-a",
            ),
            YouTubeAsrSourceCacheIdentity.legacyKeys(
                videoKey = "youtube:abc123",
                sourceCacheVersion = 9,
                subtitleModes = listOf("english", "original", "english"),
                asrCacheIdentity = "asr-a",
            ),
        )
    }
}
