package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Small100GenerationPolicyTest {
    @Test
    fun acceptsOnlyGenerationThatReachedEos() {
        Small100GenerationPolicy.requireComplete(reachedEos = true, generatedTokenCount = 1)
        try {
            Small100GenerationPolicy.requireComplete(reachedEos = false, generatedTokenCount = 40)
            fail("A token-limited partial translation must be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("未正常结束"))
        }
    }

    @Test
    fun rejectsAnEmptySequenceEvenWhenEosWasProduced() {
        try {
            Small100GenerationPolicy.requireComplete(reachedEos = true, generatedTokenCount = 0)
            fail("An empty model output must be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("未生成有效译文 token"))
        }
    }

    @Test
    fun providesAUsableOutputBudgetForLongerSubtitleCues() {
        assertEquals(256, Small100GenerationPolicy.MAX_OUTPUT_TOKENS)
    }
}
