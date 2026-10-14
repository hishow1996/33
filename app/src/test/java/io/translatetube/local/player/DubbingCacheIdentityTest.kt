package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DubbingCacheIdentityTest {
    private fun key(
        index: Int = 3,
        speakerId: Int = 14,
        text: String = "你好，欢迎观看。",
        engine: String = "Kokoro:voice-14",
        speed: Float = 1.0f,
        cueDurationMs: Long = 1_600L,
    ) = DubbingCacheIdentity.fileName(index, speakerId, text, engine, speed, cueDurationMs)

    @Test
    fun identicalSynthesisInputsProduceStableCacheFileName() {
        assertEquals(key(), key())
        assertEquals(key(), key(cueDurationMs = 1_600L))
    }

    @Test
    fun changingAnyWaveformInputInvalidatesThePcmCache() {
        val original = key()
        assertNotEquals(original, key(speakerId = 15))
        assertNotEquals(original, key(text = "你好，欢迎收看。"))
        assertNotEquals(original, key(engine = "系统TTS|voice-cmn-local"))
        assertNotEquals(original, key(speed = 1.2f))
        assertNotEquals(original, key(cueDurationMs = 1_400L))
        assertNotEquals(original, key(index = 4))
    }

    @Test
    fun invalidSpeedIsNormalizedAndNegativeDurationIsClamped() {
        assertEquals(
            key(speed = 1.0f, cueDurationMs = 0L),
            key(speed = Float.NaN, cueDurationMs = -1L),
        )
    }
}
