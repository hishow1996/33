package io.translatetube.local.translate

import io.translatetube.local.player.ProcessingErrorDisplayPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterFallbackPolicyTest {
    private class FakeEngine(
        override val displayName: String,
        private val behavior: suspend (List<String>) -> List<String>,
    ) : TranslationEngine {
        override val id: String = displayName
        var calls = 0
        override suspend fun translate(lines: List<String>, srcLang: String, tgtLang: String): List<String> {
            calls++
            return behavior(lines)
        }
    }

    @Test
    fun firstEngineThatReturnsAValidTranslationWins() = runBlocking {
        val first = FakeEngine("微软") { listOf("你好") }
        val second = FakeEngine("Google") { listOf("不该被调用") }

        val result = ContentFilterFallbackPolicy.translateRow("hello", listOf(first, second), "en", "zh") { true }

        assertEquals("你好", result.translation)
        assertEquals("微软", result.engineName)
        assertEquals(0, second.calls)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun fallsThroughToTheNextEngineWhenOneThrowsOrIsRejected() = runBlocking {
        val broken = FakeEngine("微软") { throw IllegalStateException("网络错误") }
        val rejected = FakeEngine("Google") { listOf("hello") }
        val good = FakeEngine("百度") { listOf("你好") }

        val result = ContentFilterFallbackPolicy.translateRow(
            "hello", listOf(broken, rejected, good), "en", "zh",
        ) { candidate -> candidate != "hello" }

        assertEquals("你好", result.translation)
        assertEquals("百度", result.engineName)
        assertEquals(2, result.failures.size)
        assertTrue(result.failures[0].startsWith("微软："))
        assertTrue(result.failures[1].startsWith("Google："))
    }

    @Test
    fun blankOrMissingTranslationCountsAsAFailure() = runBlocking {
        val blank = FakeEngine("微软") { listOf("  ") }
        val empty = FakeEngine("Google") { emptyList() }

        val result = ContentFilterFallbackPolicy.translateRow("hello", listOf(blank, empty), "en", "zh") { true }

        assertNull(result.translation)
        assertNull(result.engineName)
        assertEquals(2, result.failures.size)
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        val cancelling = FakeEngine("微软") { throw CancellationException("cancelled") }
        val other = FakeEngine("Google") { listOf("你好") }

        runCatching {
            runBlocking {
                ContentFilterFallbackPolicy.translateRow("hello", listOf(cancelling, other), "en", "zh") { true }
            }
        }

        // Cancellation must stop the chain instead of being treated as one engine failing.
        assertEquals(1, cancelling.calls)
        assertEquals(0, other.calls)
    }

    @Test
    fun refusedSentencesAreShownWithTheirLineNumbersAndSourceText() {
        val text = ContentFilterFallbackPolicy.describeRefusedRows(listOf(40 to "  some   refused\nsentence "))

        assertEquals("第 41 行「some refused sentence」", text)
    }

    @Test
    fun refusedSentenceListIsBounded() {
        val rows = (0 until 5).map { it to "x".repeat(300) }

        val text = ContentFilterFallbackPolicy.describeRefusedRows(rows)

        assertTrue(text.contains("第 1 行"))
        assertTrue(text.contains("第 3 行"))
        assertTrue(!text.contains("第 4 行"))
        assertTrue(text.endsWith("等共 5 句"))
        assertTrue(text.contains("x".repeat(120) + "…"))
    }

    @Test
    fun failureMessageTellsTheUserToRetryBeforeFallbackAndNamesTheSentence() {
        val message = ContentFilterFallbackPolicy.failureMessage(listOf(40 to "refused text"), fallbackTried = false)

        assertTrue(message.startsWith("字幕翻译失败：安全过滤拒绝翻译"))
        assertTrue(message.contains("第 41 行「refused text」"))
        assertTrue(message.contains("点“重试”"))
        assertTrue(message.contains("微软"))
    }

    @Test
    fun failureMessageAfterFallbackSaysTheOnlineEnginesAlsoFailed() {
        val message = ContentFilterFallbackPolicy.failureMessage(listOf(40 to "refused text"), fallbackTried = true)

        assertTrue(message.contains("也没能翻出这些句子"))
        assertTrue(message.contains("第 41 行「refused text」"))
    }

    @Test
    fun uiShowsTheRefusedSentenceInsteadOfAGenericHint() {
        val error = ContentFilterFallbackPolicy.failureMessage(listOf(40 to "refused text"), fallbackTried = false)

        val shown = ProcessingErrorDisplayPolicy.message("youtube:abc", null, "", error)

        assertTrue(shown.contains("第 41 行「refused text」"))
        assertTrue(shown.endsWith("。") || shown.endsWith("）。"))
    }

    @Test
    fun oldStyleFilterMessageWithoutSentencesStillUsesTheGenericHint() {
        val shown = ProcessingErrorDisplayPolicy.message(
            "youtube:abc",
            null,
            "",
            "字幕翻译失败：安全过滤拒绝翻译（智谱：服务商的内容安全过滤拒绝了本次请求）。原始响应：HTTP 400 host.example",
        )

        assertTrue(!shown.contains("host.example"))
    }
}
