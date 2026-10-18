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
    fun avoidsAggressiveRetryForModeratelyLongSpeech() {
        val speed = DubbingSynthesisPolicy.recommendedSpeed(
            baseSpeed = 1.0f,
            sampleCount = 40_000,
            sampleRate = 16_000,
            cueDurationMs = 1_000,
        )
        assertEquals(1.0f, speed, 0.001f)
    }

    @Test
    fun recommendsBoundedRetryOnlyForExtremeCueDurationRatios() {
        val speed = DubbingSynthesisPolicy.recommendedSpeed(
            baseSpeed = 1.0f,
            sampleCount = 64_000,
            sampleRate = 16_000,
            cueDurationMs = 1_000,
        )
        assertEquals(3.0f, speed, 0.001f)
    }

    @Test
    fun capsRetryAtMaximumSupportedSpeechRate() {
        assertEquals(
            3.0f,
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
            3.0f,
            DubbingSynthesisPolicy.recommendedSpeed(3.0f, 32_000, 16_000, 1_000),
            0.001f,
        )
    }
    @Test
    fun boundsCachedPcmToExtendedWindowForAutomaticVideoHold() {
        assertEquals(96_000, DubbingSynthesisPolicy.maxUsefulSampleCount(16_000, 1_000))
        assertEquals(132_300, DubbingSynthesisPolicy.maxUsefulSampleCount(22_050, 1_000))
        assertEquals(
            DubbingPcmValidation.MAX_SAMPLE_COUNT,
            DubbingSynthesisPolicy.maxUsefulSampleCount(192_000, 60_000),
        )
        assertEquals(0, DubbingSynthesisPolicy.maxUsefulSampleCount(0, 1_000))
        assertEquals(0, DubbingSynthesisPolicy.maxUsefulSampleCount(16_000, 0))
    }

}
