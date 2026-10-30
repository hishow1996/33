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
            Small100GenerationPolicy.requireComplete(
                reachedEos = false,
                generatedTokenCount = 40,
                outputTokenLimit = 640,
            )
            fail("A token-limited partial translation must be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("未正常结束"))
            assertTrue(error.message.orEmpty().contains("640 token"))
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
    fun reportsInputLimitWithoutRecordingSubtitleText() {
        val error = Small100InputTooLongException(actualTokens = 1200, maxTokens = 1024)
        assertTrue(error.message.orEmpty().contains("1200"))
        assertTrue(error.message.orEmpty().contains("1024"))
        assertTrue(error.message.orEmpty().contains("字幕过长"))
    }

    @Test
    fun providesAdaptiveOutputBudgetForShortAndLongSubtitleCues() {
        assertEquals(512, Small100GenerationPolicy.outputTokenLimit(1))
        assertEquals(640, Small100GenerationPolicy.outputTokenLimit(128))
        assertEquals(1024, Small100GenerationPolicy.outputTokenLimit(1024))
        assertEquals(512, Small100GenerationPolicy.outputTokenLimit(0))
        assertEquals(1024, Small100GenerationPolicy.outputTokenLimit(10_000))
        assertEquals(1024, Small100GenerationPolicy.MAX_OUTPUT_TOKENS)
    }
}
