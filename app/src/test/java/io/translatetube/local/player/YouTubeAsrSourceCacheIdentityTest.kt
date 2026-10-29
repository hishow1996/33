package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YouTubeAsrSourceCacheIdentityTest {
    @Test
    fun activeAsrSourceKeyDoesNotEncodeCaptionMode() {
        val stableKey = YouTubeAsrSourceCacheIdentity.key(
            videoKey = "youtube:abc123",
            sourceCacheVersion = 9,
            asrCacheIdentity = "asr-provider-a-config-a",
        )
        val legacyKeys = YouTubeAsrSourceCacheIdentity.legacyKeys(
            videoKey = "youtube:abc123",
            sourceCacheVersion = 9,
            subtitleModes = listOf("english", "original"),
            asrCacheIdentity = "asr-provider-a-config-a",
        )

        assertFalse(stableKey.contains("subtitle-english"))
        assertFalse(stableKey.contains("subtitle-original"))
        assertNotEquals(legacyKeys[0], legacyKeys[1])
    }

    @Test
    fun changingAsrIdentityCreatesAnIndependentTranscriptCache() {
        assertNotEquals(
            YouTubeAsrSourceCacheIdentity.key("youtube:abc123", 9, "asr-a"),
            YouTubeAsrSourceCacheIdentity.key("youtube:abc123", 9, "asr-b"),
        )
    }

    @Test
    fun legacyKeysCoverBothFormerCaptionModes() {
        assertEquals(
            listOf(
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
