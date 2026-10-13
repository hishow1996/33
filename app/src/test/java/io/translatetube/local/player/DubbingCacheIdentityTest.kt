package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCacheIdentityTest {
    @Test
    fun changingKokoroDefaultVoiceInvalidatesDefaultSpeakerCue() {
        val before = DubbingCacheIdentity.fileName(3, -1, "kokoro-v1.1-default-14", 1.0f, "你好")
        val after = DubbingCacheIdentity.fileName(3, -1, "kokoro-v1.1-default-25", 1.0f, "你好")
        assertNotEquals(before, after)
    }

    @Test
    fun changingSystemProviderOrVoiceInvalidatesCache() {
        val providerA = DubbingCacheIdentity.fileName(1, -1, "system-tts|engine=A|voice=zh-CN-x", 1.0f, "你好")
        val providerB = DubbingCacheIdentity.fileName(1, -1, "system-tts|engine=B|voice=zh-CN-y", 1.0f, "你好")
        assertNotEquals(providerA, providerB)
    }

    @Test
    fun textAndSynthesisSpeedArePartOfCacheIdentity() {
        val base = DubbingCacheIdentity.fileName(2, 5, "kokoro", 1.0f, "第一句")
        assertNotEquals(base, DubbingCacheIdentity.fileName(2, 5, "kokoro", 1.0f, "第二句"))
        assertNotEquals(base, DubbingCacheIdentity.fileName(2, 5, "kokoro", 1.2f, "第一句"))
    }

    @Test
    fun invalidSpeedIsNormalizedAndDoesNotCreateNanCacheIdentity() {
        val normal = DubbingCacheIdentity.fileName(0, -1, "engine", 1.0f, "你好")
        val invalid = DubbingCacheIdentity.fileName(0, -1, "engine", Float.NaN, "你好")
        assertEquals(normal, invalid)
    }

    @Test
    fun pcmHeaderRequiresExactPayloadLengthAndReasonableBounds() {
        assertTrue(PcmCacheFormat.isValidHeader(24_000, 0, 8L))
        assertTrue(PcmCacheFormat.isValidHeader(16_000, 100, 408L))
        assertFalse(PcmCacheFormat.isValidHeader(16_000, 100, 407L))
        assertFalse(PcmCacheFormat.isValidHeader(0, 100, 408L))
        assertFalse(PcmCacheFormat.isValidHeader(16_000, -1, 8L))
        assertFalse(PcmCacheFormat.isValidHeader(16_000, PcmCacheFormat.MAX_SAMPLES + 1, 8L))
    }
}
