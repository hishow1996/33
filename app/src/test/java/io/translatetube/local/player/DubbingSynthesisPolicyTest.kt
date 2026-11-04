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
    fun retriesModeratelyLongSpeechBeforePlaybackToFitItsTimelineWindow() {
        val speed = DubbingSynthesisPolicy.recommendedSpeed(
            baseSpeed = 1.0f,
            sampleCount = 40_000,
            sampleRate = 16_000,
            cueDurationMs = 1_000,
        )
        assertEquals(2.7778f, speed, 0.001f)
    }

    @Test
    fun secondAdaptivePassCanIncreaseTheRateWhenTheFirstRetryStillDoesNotFit() {
        val speed = DubbingSynthesisPolicy.recommendedSpeed(
            baseSpeed = 2.7778f,
            sampleCount = 24_000,
            sampleRate = 16_000,
            cueDurationMs = 1_000,
        )
        assertEquals(3.0f, speed, 0.001f)
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
    fun adaptiveSynthesisTargetLeavesHeadroomBeforePlayback() {
        assertEquals(true, DubbingSynthesisPolicy.isWithinTimelineTarget(900.0, 1_000L))
        assertEquals(false, DubbingSynthesisPolicy.isWithinTimelineTarget(1_001.0, 1_000L))
        assertEquals(false, DubbingSynthesisPolicy.isWithinTimelineTarget(1_700.0, 1_000L))
        assertEquals(false, DubbingSynthesisPolicy.isWithinTimelineTarget(Double.NaN, 1_000L))
        assertEquals(false, DubbingSynthesisPolicy.isWithinTimelineTarget(100.0, 0L))
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
    fun preservesFullPcmInsteadOfCroppingAtACueDurationRatio() {
        assertEquals(
            DubbingPcmValidation.MAX_SAMPLE_COUNT,
            DubbingSynthesisPolicy.maxUsefulSampleCount(16_000, 1_000),
        )
        assertEquals(
            DubbingPcmValidation.MAX_SAMPLE_COUNT,
            DubbingSynthesisPolicy.maxUsefulSampleCount(22_050, 1_000),
        )
        assertEquals(
            DubbingPcmValidation.MAX_SAMPLE_COUNT,
            DubbingSynthesisPolicy.maxUsefulSampleCount(192_000, 60_000),
        )
        assertEquals(0, DubbingSynthesisPolicy.maxUsefulSampleCount(0, 1_000))
        assertEquals(0, DubbingSynthesisPolicy.maxUsefulSampleCount(16_000, 0))
    }

}
