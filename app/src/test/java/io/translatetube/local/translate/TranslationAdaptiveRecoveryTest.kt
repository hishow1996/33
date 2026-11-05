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
                List(rows.size) { "" }
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
        val result = translateBatchWithAdaptiveRecovery(
            lines = listOf("first", "unresolved", "last"),
            isAcceptableTranslation = { _, candidate -> candidate.startsWith("中文:") },
            deferInvalidRowsToCaller = true,
            translate = { rows, _ ->
                if (rows.size > 1) {
                    listOf("截断响应")
                } else {
                    when (rows.single()) {
                        "first" -> listOf("中文:first")
                        "unresolved" -> listOf("")
                        "last" -> listOf("中文:last")
                        else -> listOf("")
                    }
                }
            },
        )

        assertEquals(listOf("中文:first", "", "中文:last"), result)
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
        assertTrue(error?.message.orEmpty().contains("翻译失败"))
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
