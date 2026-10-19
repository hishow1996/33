package io.translatetube.local.tts

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTtsTextChunkPolicyTest {
    @Test
    fun keepsShortTextAsOneChunk() {
        val text = "你好，世界！"
        assertEquals(listOf(text), WebTtsTextChunkPolicy.split(text))
    }

    @Test
    fun everyChunkFitsEncodedUrlQueryBudget() {
        val text = "中文配音🙂".repeat(700)
        val chunks = WebTtsTextChunkPolicy.split(text)

        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(
                URLEncoder.encode(chunk, "UTF-8").length <=
                    WebTtsTextChunkPolicy.MAX_ENCODED_QUERY_CHARS
            )
        }
    }

    @Test
    fun preservesEveryCodePointAndDoesNotSplitSurrogatePairs() {
        val text = ("字幕🙂语音🚀测试".repeat(500))
        val chunks = WebTtsTextChunkPolicy.split(text)

        assertEquals(text, chunks.joinToString(separator = ""))
        chunks.forEach { chunk ->
            assertFalse(chunk.startsWithLowSurrogate())
            assertFalse(chunk.endsWithHighSurrogate())
        }
    }

    @Test
    fun prefersSentenceBoundariesNearSoftBudget() {
        val text = "这是一个完整句子。".repeat(250)
        val chunks = WebTtsTextChunkPolicy.split(text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.dropLast(1).all { it.endsWith("。") })
    }

    @Test
    fun handlesUnbrokenTextWithoutExceedingBudget() {
        val text = "a".repeat(WebTtsTextChunkPolicy.MAX_ENCODED_QUERY_CHARS * 2 + 13)
        val chunks = WebTtsTextChunkPolicy.split(text)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { URLEncoder.encode(it, "UTF-8").length <= WebTtsTextChunkPolicy.MAX_ENCODED_QUERY_CHARS })
    }

    private fun String.startsWithLowSurrogate(): Boolean =
        isNotEmpty() && this[0].isLowSurrogate()

    private fun String.endsWithHighSurrogate(): Boolean =
        isNotEmpty() && this[lastIndex].isHighSurrogate()
}
