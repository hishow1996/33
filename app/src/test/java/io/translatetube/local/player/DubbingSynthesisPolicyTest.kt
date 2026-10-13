package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingSynthesisPolicyTest {
    @Test
    fun doesNotRetryWhenSpeechAlreadyFitsCue() {
        assertEquals(
            1.0f,
            DubbingSynthesisPolicy.recommendedSpeed(1.0f, 16_000, 16_000, 1_000),
            0.001f,
        )
    }

    @Test
    fun recommendsBoundedRetryWhenSpeechIsTooLong() {
        val speed = DubbingSynthesisPolicy.recommendedSpeed(
            baseSpeed = 1.0f,
            sampleCount = 24_000,
            sampleRate = 16_000,
            cueDurationMs = 1_000,
        )
        assertEquals(1.6667f, speed, 0.01f)
    }

    @Test
    fun capsRetryAtMaximumSupportedSpeechRate() {
        assertEquals(
            2.0f,
            DubbingSynthesisPolicy.recommendedSpeed(1.0f, 80_000, 16_000, 1_000),
            0.001f,
        )
    }

    @Test
    fun invalidAudioOrCueReturnsNormalizedBaseSpeed() {
        assertEquals(
            1.0f,
            DubbingSynthesisPolicy.recommendedSpeed(Float.NaN, 0, 16_000, 0),
            0.001f,
        )
        assertEquals(
            2.0f,
            DubbingSynthesisPolicy.recommendedSpeed(3.0f, 32_000, 16_000, 1_000),
            0.001f,
        )
    }
}
