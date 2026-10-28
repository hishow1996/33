package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionParamsPolicyTest {
    private val gateway = "https://gateway.example/v1"
    private val openAi = "https://api.openai.com/v1"

    @Test
    fun ordinaryModelsKeepTheOriginalRequestShape() {
        for (model in listOf("gpt-4o-mini", "deepseek-chat", "qwen-plus", "gemini-2.5-flash", "glm-4-flash")) {
            val params = ChatCompletionParamsPolicy.forModel(gateway, model)
            assertEquals(model, 0.2, params.temperature)
            assertEquals(model, 2048L, params.maxTokens.toLong())
            assertFalse(model, params.useMaxCompletionTokens)
        }
    }

    @Test
    fun thinkingOnlyModelsGetRoomForTheirReasoning() {
        for (model in listOf("deepseek-r1", "DeepSeek-Reasoner", "QwQ-32B", "qwen3-thinking", "vendor/some-thinking-model")) {
            val params = ChatCompletionParamsPolicy.forModel(gateway, model)
            assertEquals(model, 8192L, params.maxTokens.toLong())
            assertEquals(model, 0.2, params.temperature)
            assertFalse(model, params.useMaxCompletionTokens)
        }
    }

    @Test
    fun openAiReasoningModelsDropTemperatureAndGetMoreRoom() {
        for (model in listOf("o1", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini", "GPT-5.1")) {
            val params = ChatCompletionParamsPolicy.forModel(gateway, model)
            assertNull(model, params.temperature)
            assertEquals(model, 8192L, params.maxTokens.toLong())
        }
    }

    @Test
    fun maxCompletionTokensIsOnlyUsedOnOfficialOpenAiEndpoints() {
        assertTrue(ChatCompletionParamsPolicy.forModel(openAi, "gpt-5").useMaxCompletionTokens)
        assertTrue(ChatCompletionParamsPolicy.forModel("https://x.openai.azure.com/openai", "o3-mini").useMaxCompletionTokens)
        assertFalse(ChatCompletionParamsPolicy.forModel(gateway, "gpt-5").useMaxCompletionTokens)
        // 官方 OpenAI 的普通模型仍然用 max_tokens
        assertFalse(ChatCompletionParamsPolicy.forModel(openAi, "gpt-4o-mini").useMaxCompletionTokens)
    }

    @Test
    fun modelNamesThatMerelyContainO1OrO3LettersAreNotReasoningModels() {
        for (model in listOf("gpt-4o", "gpt-4o-mini", "yi-lightning", "llama-3.1-70b", "glm-4o1x")) {
            assertFalse(model, ChatCompletionParamsPolicy.isOpenAiReasoningModel(model.lowercase()))
        }
    }

    @Test
    fun thinkingOnlyDetectionMatchesTheEarlierRules() {
        assertTrue(ChatCompletionParamsPolicy.isThinkingOnlyModel("qwq"))
        assertTrue(ChatCompletionParamsPolicy.isThinkingOnlyModel("deepseek-r1-distill"))
        assertFalse(ChatCompletionParamsPolicy.isThinkingOnlyModel("deepseek-chat"))
        assertFalse(ChatCompletionParamsPolicy.isThinkingOnlyModel("qwen3.8-flash"))
    }

    @Test
    fun lengthTruncationIsRecognisedAcrossProviderSpellings() {
        assertTrue(ChatCompletionParamsPolicy.isLengthTruncation("length"))
        assertTrue(ChatCompletionParamsPolicy.isLengthTruncation(" LENGTH "))
        assertTrue(ChatCompletionParamsPolicy.isLengthTruncation("max_tokens"))
        assertFalse(ChatCompletionParamsPolicy.isLengthTruncation("stop"))
        assertFalse(ChatCompletionParamsPolicy.isLengthTruncation("未知"))
        assertFalse(ChatCompletionParamsPolicy.isLengthTruncation(null))
    }

    @Test
    fun zhipuV4BaseDoesNotGetAnExtraV1Segment() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            openAiCompatEndpoint("https://open.bigmodel.cn/api/paas/v4", "/chat/completions")
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            openAiCompatEndpoint("https://open.bigmodel.cn/api/paas/v4/", "/models")
        )
    }

    @Test
    fun alreadyVersionedCompatibleBaseKeepsItsVersion() {
        assertEquals(
            "https://gateway.example/api/v3/chat/completions",
            openAiCompatEndpoint("https://gateway.example/api/v3", "/chat/completions")
        )
        assertEquals(
            "https://gateway.example/v1/models",
            openAiCompatEndpoint("https://gateway.example/v1/", "/models")
        )
    }

    @Test
    fun unversionedCompatibleBaseStillGetsV1() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            openAiCompatEndpoint("https://api.deepseek.com", "/chat/completions")
        )
    }


    @Test
    fun fullEndpointBaseUrlIsNormalizedForBothChatAndModelRoutes() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            openAiCompatEndpoint(
                "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                "/chat/completions"
            )
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            openAiCompatEndpoint(
                "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                "/models"
            )
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            openAiCompatEndpoint(
                "https://open.bigmodel.cn/api/paas/v4/models",
                "/chat/completions"
            )
        )
    }

    @Test
    fun baseUrlQueryParametersRemainAfterTheEndpointPath() {
        assertEquals(
            "https://gateway.example/api/v3/chat/completions?tenant=abc",
            openAiCompatEndpoint(
                "https://gateway.example/api/v3?tenant=abc",
                "/chat/completions"
            )
        )
    }

    @Test
    fun invalidBaseUrlFailsWithActionableMessage() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            openAiCompatEndpoint("not a url", "/chat/completions")
        }
        assertTrue(error.message.orEmpty().contains("API Base URL 格式无效"))
    }

}
