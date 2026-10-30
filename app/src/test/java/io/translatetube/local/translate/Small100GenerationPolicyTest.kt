package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            assertTrue(error is Small100OutputLimitException)
        }
    }

    @Test
    fun rejectsAnEmptySequenceEvenWhenEosWasProduced() {
        try {
            Small100GenerationPolicy.requireComplete(reachedEos = true, generatedTokenCount = 0)
            fail("An empty model output must be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("未生成有效译文 token"))
            assertFalse(error is Small100OutputLimitException)
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

    @Test
    fun splitsAtSentenceBoundaryAndPreservesExactSourceText() {
        val source = "Hola, ¿cómo estás? Estoy muy bien. Gracias."
        val result = Small100GenerationPolicy.splitForRecovery(
            text = source,
            maxChunkTokens = 40,
            tokenCount = { it.length },
        )
        assertNotNull(result)
        assertTrue(result!!.first.endsWith("?") || result.first.endsWith("."))
        assertEquals(source, result.first + result.second)
        assertTrue(result.first.isNotBlank())
        assertTrue(result.second.isNotBlank())
    }

    @Test
    fun splitsAtWhitespaceWhenNoSentenceBoundaryExists() {
        val source = "palabra uno dos tres cuatro cinco seis siete"
        val result = Small100GenerationPolicy.splitForRecovery(
            text = source,
            maxChunkTokens = 30,
            tokenCount = { it.length },
        )
        assertNotNull(result)
        assertTrue(result!!.first.last().isWhitespace())
        assertEquals(source, result.first + result.second)
    }

    @Test
    fun fallsBackToUnicodeSafeCodePointBoundary() {
        val source = "😀😀😀😀😀"
        val result = Small100GenerationPolicy.splitForRecovery(
            text = source,
            maxChunkTokens = 2,
            tokenCount = { it.codePointCount(0, it.length) },
        )
        assertNotNull(result)
        assertEquals(source, result!!.first + result.second)
        assertFalse(Character.isHighSurrogate(result.first.last()))
        assertFalse(Character.isLowSurrogate(result.second.first()))
        assertTrue(result.first.endsWith("😀"))
        assertTrue(result.second.startsWith("😀"))
    }

    @Test
    fun doesNotSplitSingleCodePointOrBlankText() {
        assertNull(
            Small100GenerationPolicy.splitForRecovery(
                text = "😀",
                maxChunkTokens = 1,
                tokenCount = { it.length },
            )
        )
        assertNull(
            Small100GenerationPolicy.splitForRecovery(
                text = "  ",
                maxChunkTokens = 1,
                tokenCount = { it.length },
            )
        )
    }
}
