package io.translatetube.local.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAdaptiveRecoveryTest {
    @Test
    fun incompleteRowsNeverCauseTheOriginalBatchToBeResized() = runBlocking {
        val calls = mutableListOf<Int>()
        val sources = (0 until 20).map { "source-$it" }
        val failure = runCatching {
            translateBatchWithAdaptiveRecovery(lines = sources) { rows, _ ->
                calls += rows.size
                List(rows.size) { "" }
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(20), calls)
        assertTrue(failure?.message.orEmpty().contains("固定批次 20 条"))
        assertTrue(failure?.message.orEmpty().contains("不会自动缩小后续批次"))
    }

    @Test
    fun malformedResponseRetriesTheSameRowsWithTheSameContext() = runBlocking {
        val requestSizes = mutableListOf<Int>()
        val contexts = mutableListOf<Pair<List<String>, List<String>>?>()
        val sources = List(10) { ('a' + it).toString() }
        val initialContext = listOf("previous") to listOf("此前译文")
        val result = translateBatchWithAdaptiveRecovery(
            lines = sources,
            context = initialContext,
            retryMalformedBatch = { rows, context ->
                requestSizes += rows.size
                contexts += context
                rows.map { "译:$it" }
            },
            translate = { rows, context ->
                requestSizes += rows.size
                contexts += context
                throw IllegalStateException("翻译服务返回的字幕编号不完整，已拒绝可能错位的译文")
            },
        )

        assertEquals(sources.map { "译:$it" }, result)
        assertEquals(listOf(10, 10), requestSizes)
        assertEquals(listOf(initialContext, initialContext), contexts)
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
    fun repeatedNumberingFailureStopsAfterOneRetryWithoutShrinkingTheBatch() = runBlocking {
        val requestSizes = mutableListOf<Int>()
        val sources = List(10) { "cue-$it" }
        val failure = runCatching {
            translateBatchWithAdaptiveRecovery(
                lines = sources,
                retryMalformedBatch = { rows, _ ->
                    requestSizes += rows.size
                    throw IllegalStateException("翻译服务返回的字幕编号不完整，已拒绝可能错位的译文")
                },
                translate = { rows, _ ->
                    requestSizes += rows.size
                    throw IllegalStateException("翻译服务返回的字幕编号不完整，已拒绝可能错位的译文")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(10, 10), requestSizes)
        assertTrue(failure?.message.orEmpty().contains("固定批次 10 条"))
        assertTrue(failure?.message.orEmpty().contains("不会自动缩小后续批次"))
    }

    @Test
    fun retriesOnlyMissingRowsAndKeepsAlreadyTranslatedRows() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val seenContexts = mutableListOf<Pair<List<String>, List<String>>?>()
        val result = translateBatchWithAdaptiveRecovery(listOf("a", "b", "c", "d")) { rows, context ->
            calls += rows
            seenContexts += context
            if (rows == listOf("a", "b", "c", "d")) {
                listOf("译:a", "", "译:c", "")
            } else {
                rows.map { "译:$it" }
            }
        }

        assertEquals(listOf("译:a", "译:b", "译:c", "译:d"), result)
        assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("b"), listOf("d")), calls)
        assertTrue(seenContexts[1]?.first?.lastOrNull() == "a")
        assertTrue(seenContexts[2]?.first?.takeLast(3) == listOf("a", "b", "c"))
    }

    @Test
    fun retriesOnlySourceEchoRowsBeforeReturningBatch() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val sourceLanguage = "en"
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("Hello world", "Good morning"),
            isAcceptableTranslation = { original, candidate ->
                !SubtitleTranslationQualityPolicy.isSourceEcho(original, candidate, sourceLanguage)
            },
        ) { rows, _ ->
            calls += rows
            if (rows.size == 2) {
                listOf("Hello world", "早上好")
            } else {
                listOf("你好，世界")
            }
        }

        assertEquals(listOf("你好，世界", "早上好"), result)
        assertEquals(listOf(listOf("Hello world", "Good morning"), listOf("Hello world")), calls)
    }

    @Test
    fun defersAlignedRejectedRowsToTheCallerWithoutAnExtraHiddenRequest() = runBlocking {
        val calls = mutableListOf<String>()
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("Hello", "Thanks"),
            isAcceptableTranslation = { source, candidate -> source != candidate },
            retrySingle = { source, _ ->
                calls += "hidden:$source"
                "should-not-run"
            },
            deferInvalidRowsToCaller = true,
            translate = { rows, _ ->
                calls += rows.joinToString(",")
                if (rows.size == 2) listOf("你好", "Thanks") else rows.map { it }
            },
        )

        assertEquals(listOf("你好", "Thanks"), result)
        assertEquals(listOf("Hello,Thanks"), calls)
    }

    @Test
    fun returnsUnresolvedRowsAfterSafeAlignmentRecoveryWhenCallerOwnsRetry() = runBlocking {
        val sources = listOf("first", "unresolved", "last") + List(7) { "cue-$it" }
        val result = translateBatchWithAdaptiveRecovery(
            lines = sources,
            isAcceptableTranslation = { _, candidate -> candidate.startsWith("中文:") },
            deferInvalidRowsToCaller = true,
            translate = { rows, _ ->
                rows.map { source -> if (source == "unresolved") "" else "中文:$source" }
            },
        )

        assertEquals(sources.map { if (it == "unresolved") "" else "中文:$it" }, result)
    }

    @Test
    fun sourceEchoThatPersistsInSingleRowIsHandedBackAfterOneRetry() = runBlocking {
        var calls = 0
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("Bonjour"),
            isAcceptableTranslation = { source, candidate ->
                !SubtitleTranslationQualityPolicy.isSourceEcho(source, candidate, "fr")
            },
        ) { rows, _ ->
            calls++
            rows
        }

        // One stubborn cue must not abort the whole batch; the caller treats it as a missing row.
        assertEquals(listOf("Bonjour"), result)
        assertEquals(2, calls)
    }

    @Test
    fun retriesAnEchoedSingleCueWithAForcedTranslationRequest() = runBlocking {
        val calls = mutableListOf<String>()
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("Thanks"),
            isAcceptableTranslation = { source, candidate -> source != candidate },
            retrySingle = { source, _ ->
                calls += "strict:$source"
                "谢谢"
            },
            translate = { rows, _ ->
                calls += "normal:${rows.size}"
                rows
            },
        )

        assertEquals(listOf("谢谢"), result)
        assertEquals(listOf("normal:1", "strict:Thanks"), calls)
    }

    @Test
    fun reportsWhySingleCueRecoveryWasRejectedWithoutLoggingSubtitleText() = runBlocking {
        // The first MAX_ISOLATED_CUE_FAILURES_PER_BATCH stubborn cues are tolerated; the next one
        // fails the batch and its message must explain why without leaking subtitle text.
        val lines = listOf("Thanks", "Welcome", "Goodbye")
        assertEquals(MAX_ISOLATED_CUE_FAILURES_PER_BATCH + 1, lines.size)
        val error = runCatching {
            translateBatchWithAdaptiveRecovery(
                lines = lines,
                isAcceptableTranslation = { source, candidate -> source != candidate },
                retrySingle = { source, _ -> source },
                translate = { rows, _ -> rows },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        val message = error?.message.orEmpty()
        assertTrue(message.contains("单条恢复请求仍与原文相同"))
        assertTrue(message.contains("原文字符数=7"))
        lines.forEach { assertTrue(!message.contains(it)) }
    }

    @Test
    fun toleratesIsolatedStubbornCuesWhileKeepingTheRestOfTheBatch() = runBlocking {
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("Thanks", "Welcome", "Goodbye"),
            isAcceptableTranslation = { source, candidate -> source != candidate },
            retrySingle = { source, _ -> if (source == "Welcome") source else "译:$source" },
            translate = { rows, _ -> rows },
        )

        // Only the stubborn cue stays unusable; the caller treats it as a missing row.
        assertEquals(listOf("译:Thanks", "Welcome", "译:Goodbye"), result)
    }



    @Test
    fun doesNotReturnUnresolvedCuesAfterAnUnalignedResponseWasSeen() = runBlocking {
        val error = runCatching {
            translateBatchWithAdaptiveRecovery(
                lines = listOf("first", "unresolved", "last"),
            ) { rows, _ ->
                when (rows) {
                    listOf("first") -> listOf("中文:first")
                    listOf("unresolved") -> listOf("")
                    listOf("last") -> listOf("中文:last")
                    else -> listOf("truncated provider response")
                }
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("固定批次 3 条"))
    }

    @Test
    fun doesNotTreatWrappedNetworkFailureAsRecoverableFormatError() = runBlocking {
        val networkFailure = IllegalStateException(
            "翻译服务返回的字幕编号不完整",
            java.io.IOException("connection reset"),
        )
        var calls = 0
        val error = runCatching {
            translateBatchWithAdaptiveRecovery(listOf("a", "b", "c")) { _, _ ->
                calls++
                throw networkFailure
            }
        }.exceptionOrNull()

        assertTrue(error === networkFailure)
        assertEquals(1, calls)
    }

}
