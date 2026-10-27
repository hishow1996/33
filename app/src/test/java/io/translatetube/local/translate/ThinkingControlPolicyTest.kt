package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingControlPolicyTest {
    @Test
    fun deepSeekModelUsesItsNativeControlAndOnlyOneFallback() {
        val controls = thinkingControlOrder("https://opc.fiime.cn", "DeepSeek V4 Flash")

        assertEquals(ThinkingControlStyle.DEEPSEEK, controls.first())
        assertEquals(2, controls.size)
        assertEquals(ThinkingControlStyle.REASONING_EFFORT, controls.last())
    }

    @Test
    fun qwenModelUsesQwenControlAndOneFallback() {
        val controls = thinkingControlOrder("https://gateway.example/v1", "qwen3-thinking")

        assertEquals(ThinkingControlStyle.QWEN, controls.first())
        assertEquals(2, controls.size)
        assertEquals(ThinkingControlStyle.REASONING_EFFORT, controls.last())
    }

    @Test
    fun openRouterEndpointUsesOpenRouterControl() {
        val controls = thinkingControlOrder("https://openrouter.ai/api/v1", "some-reasoning-model")

        assertEquals(ThinkingControlStyle.OPENROUTER, controls.first())
        assertEquals(2, controls.size)
    }

    @Test
    fun unknownModelUsesUnmodifiedRequestFirst() {
        val controls = thinkingControlOrder("https://gateway.example/v1", "vendor-model-2026")

        assertEquals(ThinkingControlStyle.PLAIN, controls.first())
        assertEquals(listOf(ThinkingControlStyle.PLAIN, ThinkingControlStyle.REASONING_EFFORT), controls)
    }

    @Test
    fun reasoningModelsUseEffortControlAndPlainFallback() {
        val controls = thinkingControlOrder("https://gateway.example/v1", "gpt-5-reasoning")

        assertEquals(ThinkingControlStyle.REASONING_EFFORT, controls.first())
        assertEquals(ThinkingControlStyle.PLAIN, controls.last())
        assertTrue(controls.size <= 2)
    }
    @Test
    fun unsupportedThinkingParameterIsRecognizedForFallback() {
        assertTrue(
            isThinkingControlRejected(
                IllegalStateException("HTTP 400: unsupported parameter 'reasoning_effort'")
            )
        )
        assertTrue(
            isThinkingControlRejected(
                IllegalStateException("HTTP 400: unknown field enable_thinking")
            )
        )
    }

    @Test
    fun authenticationQuotaAndServerErrorsDoNotTriggerParameterFallback() {
        assertTrue(
            !isThinkingControlRejected(IllegalStateException("HTTP 401: invalid API key"))
        )
        assertTrue(
            !isThinkingControlRejected(IllegalStateException("HTTP 429: rate limit exceeded"))
        )
        assertTrue(
            !isThinkingControlRejected(IllegalStateException("HTTP 503: service unavailable"))
        )
    }
}
