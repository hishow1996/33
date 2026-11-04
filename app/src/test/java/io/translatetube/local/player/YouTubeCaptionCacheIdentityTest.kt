package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCaptionCacheIdentityTest {
    @Test
    fun captionKeyDoesNotDependOnAsrEngineIdentity() {
        val prefix = YouTubeCaptionCacheIdentity.prefix("youtube:video-1", 3, "prefer_english")
        assertEquals(
            "youtube:video-1|source|youtube-v3-subtitle-prefer_english|captions-v2",
            YouTubeCaptionCacheIdentity.key("youtube:video-1", 3, "prefer_english"),
        )
        assertTrue((prefix + "asr-local-whisper-base|captions-v1").startsWith(prefix))
        assertTrue((prefix + "asr-cartesia-auto-cloud-v1|captions-v1").startsWith(prefix))
    }

    @Test
    fun captionKeysStaySeparatedByVideoSubtitleModeAndVersion() {
        val baseline = YouTubeCaptionCacheIdentity.key("youtube:video-1", 3, "prefer_english")
        assertFalse(baseline == YouTubeCaptionCacheIdentity.key("youtube:video-2", 3, "prefer_english"))
        assertFalse(baseline == YouTubeCaptionCacheIdentity.key("youtube:video-1", 3, "original_audio"))
        assertFalse(baseline == YouTubeCaptionCacheIdentity.key("youtube:video-1", 4, "prefer_english"))
    }

    @Test
    fun recognizesOldAndNewCaptionKeysButNotAsrTranscriptKeys() {
        assertTrue(YouTubeCaptionCacheIdentity.isCaptionCacheKey("video|captions-v1"))
        assertTrue(YouTubeCaptionCacheIdentity.isCaptionCacheKey("video|captions-v2"))
        assertFalse(YouTubeCaptionCacheIdentity.isCaptionCacheKey("video|source|asr-local"))
        assertFalse(YouTubeCaptionCacheIdentity.isCaptionCacheKey(null))
    }
}
