package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCacheReadGenerationPolicyTest {
    @Test
    fun acceptsPcmOnlyWhenPlaybackAndSynthesisGenerationsMatch() {
        assertTrue(
            DubbingCacheReadGenerationPolicy.canContinue(
                expectedGeneration = 7,
                currentGeneration = 7,
                playbackIsCurrent = true,
            ),
        )
    }

    @Test
    fun rejectsPcmReadAfterSynthesisConfigurationChanges() {
        assertFalse(
            DubbingCacheReadGenerationPolicy.canContinue(
                expectedGeneration = 7,
                currentGeneration = 8,
                playbackIsCurrent = true,
            ),
        )
    }

    @Test
    fun rejectsPcmReadAfterSeekOrPlaybackReplacement() {
        assertFalse(
            DubbingCacheReadGenerationPolicy.canContinue(
                expectedGeneration = 7,
                currentGeneration = 7,
                playbackIsCurrent = false,
            ),
        )
    }
}
