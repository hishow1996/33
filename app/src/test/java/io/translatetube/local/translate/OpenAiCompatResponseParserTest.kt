package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatResponseParserTest {
    @Test
    fun extractsStandardChatCompletionText() {
        val response = """
            {
              "choices": [
                {
                  "message": { "role": "assistant", "content": "0|你好" },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()

        assertEquals("0|你好", extractOpenAiCompatContent(response))
    }

    @Test
    fun extractsStructuredContentBlocks() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": [
                      { "type": "text", "text": "0|你好" }
                    ]
                  },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()

        assertEquals("0|你好", extractOpenAiCompatContent(response))
    }

    @Test
    fun extractsResponsesStyleFinalTextAndSkipsReasoningBlocks() {
        val response = """
            {
              "object": "response",
              "output": [
                {
                  "type": "reasoning",
                  "content": [
                    { "type": "reasoning_text", "text": "private reasoning" }
                  ]
                },
                {
                  "type": "message",
                  "content": [
                    { "type": "output_text", "text": "0|最终译文" }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertEquals("0|最终译文", extractOpenAiCompatContent(response))
    }

    @Test
    fun reasoningOnlyResponsesAreReportedAsRecoverableThinkingResponses() {
        val response = """
            {
              "object": "response",
              "status": "incomplete",
              "output": [
                {
                  "type": "reasoning",
                  "content": [
                    { "type": "reasoning_text", "text": "reasoning only" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val error = runCatching { extractOpenAiCompatContent(response) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("推理字段"))
    }

    @Test
    fun providerErrorObjectIsNotMisreadAsMissingContent() {
        val response = """
            {
              "error": {
                "code": "insufficient_quota",
                "message": "quota exceeded"
              }
            }
        """.trimIndent()

        val error = runCatching { extractOpenAiCompatContent(response) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("insufficient_quota"))
        assertTrue(error?.message.orEmpty().contains("quota exceeded"))
    }

    @Test
    fun topLevelOutputTextIsSupported() {
        val response = """{ "output_text": "0|简体中文" }"""

        assertEquals("0|简体中文", extractOpenAiCompatContent(response))
    }

    @Test
    fun topLevelFinalTextAliasesAreSupported() {
        val variants = listOf(
            """{ "final_answer": "0|最终答案" }""",
            """{ "final": "0|最终文本" }""",
            """{ "answer": "0|答案字段" }""",
            """{ "translated_text": "0|翻译字段" }""",
            """{ "translation": "0|translation 字段" }""",
            """{ "text": "0|text 字段" }""",
        )

        assertEquals("0|最终答案", extractOpenAiCompatContent(variants[0]))
        assertEquals("0|最终文本", extractOpenAiCompatContent(variants[1]))
        assertEquals("0|答案字段", extractOpenAiCompatContent(variants[2]))
        assertEquals("0|翻译字段", extractOpenAiCompatContent(variants[3]))
        assertEquals("0|translation 字段", extractOpenAiCompatContent(variants[4]))
        assertEquals("0|text 字段", extractOpenAiCompatContent(variants[5]))
    }

    @Test
    fun extractsNestedContentValueFromGatewayObjects() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": {
                      "type": "text",
                      "content": { "value": "0|多层嵌套译文" }
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals("0|多层嵌套译文", extractOpenAiCompatContent(response))
    }

    @Test
    fun extractsObjectStyleMessageContent() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": {
                      "type": "text",
                      "text": "0|对象格式译文"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals("0|对象格式译文", extractOpenAiCompatContent(response))
    }

    @Test
    fun extractsLegacyChoiceText() {
        val response = """{ "choices": [ { "text": "0|旧格式译文" } ] }"""

        assertEquals("0|旧格式译文", extractOpenAiCompatContent(response))
    }

    @Test
    fun recognizesStringStyleProviderErrors() {
        val response = """{ "error": "upstream model unavailable" }"""

        val error = runCatching { extractOpenAiCompatContent(response) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("upstream model unavailable"))
    }

    @Test
    fun providerErrorIncludesNumericCodeTypeAndParameter() {
        val response = """
            {
              "error": {
                "code": 12345,
                "type": "invalid_request_error",
                "param": "reasoning_effort",
                "message": "unsupported value"
              }
            }
        """.trimIndent()

        val error = runCatching { extractOpenAiCompatContent(response) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("[12345]"))
        assertTrue(error?.message.orEmpty().contains("type=invalid_request_error"))
        assertTrue(error?.message.orEmpty().contains("param=reasoning_effort"))
        assertTrue(error?.message.orEmpty().contains("unsupported value"))
    }

    @Test
    fun malformedJsonGetsClearParserError() {
        val error = runCatching { extractOpenAiCompatContent("{broken json") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("不是有效的 JSON"))
    }
}
