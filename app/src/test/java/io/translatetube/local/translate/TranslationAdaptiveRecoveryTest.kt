package io.translatetube.local.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAdaptiveRecoveryTest {
    @Test
    fun splitsLargeRequestsWhenTheModelReturnsIncompleteRows() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = translateBatchWithAdaptiveRecovery(
            lines = (0 until 8).map { "source-$it" },
        ) { rows, _ ->
            calls += rows.size
            if (rows.size > 2) {
                List(rows.size) { index -> if (index == 0) "译文" else "" }
            } else {
                rows.map { "中文：$it" }
            }
        }

        assertEquals((0 until 8).map { "中文：source-$it" }, result)
        assertTrue(calls.any { it == 8 })
        assertTrue(calls.any { it == 4 })
        assertTrue(calls.any { it == 2 })
    }

    @Test
    fun passesPriorContextAndCarriesFreshlyTranslatedLeftHalfForward() = runBlocking {
        val contexts = mutableListOf<Pair<List<String>, List<String>>?>()
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("a", "b", "c", "d"),
            context = listOf("previous") to listOf("此前译文"),
        ) { rows, context ->
            contexts += context
            if (rows.size > 1) {
                List(rows.size) { "" }
            } else {
                rows.map { "译:$it" }
            }
        }

        assertEquals(listOf("译:a", "译:b", "译:c", "译:d"), result)
        assertTrue(contexts.any { it?.first?.contains("previous") == true })
        assertTrue(contexts.any {
            it != null &&
                it.first.takeLast(2) == listOf("a", "b") &&
                it.second.takeLast(2) == listOf("译:a", "译:b")
        })
    }

    @Test
    fun doesNotRecursivelySplitPermanentProviderErrors() = runBlocking {
        var calls = 0
        val failure = runCatching {
            translateBatchWithAdaptiveRecovery(listOf("a", "b", "c")) { _, _ ->
                calls++
                throw IllegalStateException("HTTP 401: invalid API key")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, calls)
    }

    @Test
    fun emptyInputDoesNotCallTheProvider() = runBlocking {
        var calls = 0
        val result = translateBatchWithAdaptiveRecovery(emptyList()) { _, _ ->
            calls++
            emptyList()
        }
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun splitsOnlyRecognizedIncompleteNumberedOutputErrors() = runBlocking {
        val sizes = mutableListOf<Int>()
        val result = translateBatchWithAdaptiveRecovery(listOf("a", "b", "c", "d")) { rows, _ ->
            sizes += rows.size
            if (rows.size > 2) {
                throw IllegalStateException("翻译服务返回的字幕编号不完整，已拒绝可能错位的译文")
            }
            rows.map { "中:$it" }
        }

        assertEquals(listOf("中:a", "中:b", "中:c", "中:d"), result)
        assertTrue(sizes.contains(4))
        assertTrue(sizes.count { it == 2 } >= 2)
    }
}
