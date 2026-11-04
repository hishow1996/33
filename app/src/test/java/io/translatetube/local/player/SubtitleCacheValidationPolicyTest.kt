package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCacheValidationPolicyTest {
    @Test
    fun acceptsOrderedAndOverlappingSubtitleCues() {
        assertTrue(
            SubtitleCacheValidationPolicy.hasValidTimeline(
                listOf(
                    Triple(0L, 1_000L, "first"),
                    Triple(500L, 1_500L, "overlapping"),
                    Triple(1_500L, 2_000L, "next"),
                ).sortedBy { it.first }
            )
        )
    }

    @Test
    fun recoversMixedTranslationCacheWhenSomeRowsArePlaceholders() {
        assertTrue(
            SubtitleCacheValidationPolicy.hasRecoverableTranslationRows(
                listOf("第一句", "正在翻译2/4行", "", null, "第三句")
            )
        )
        assertFalse(
            SubtitleCacheValidationPolicy.hasRecoverableTranslationRows(
                listOf("正在翻译", "翻译中3/4行", null, "")
            )
        )
        assertFalse(SubtitleCacheValidationPolicy.hasRecoverableTranslationRows(emptyList()))
    }

    @Test
    fun restoresChineseSourceTextOverBlankOrStaleCachedTranslations() {
        val rows = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 1_000L, "你好", "正在翻译"),
            io.translatetube.local.subs.SubLine(1, 1_000L, 2_000L, "世界", null),
            io.translatetube.local.subs.SubLine(2, 2_000L, 3_000L, "继续", "旧的错误译文"),
            io.translatetube.local.subs.SubLine(3, 3_000L, 4_000L, "哈哈哈哈", "旧的错误译文"),
            io.translatetube.local.subs.SubLine(4, 4_000L, 5_000L, "正在翻译", null),
        )

        val restored = SubtitleCacheValidationPolicy.restoreChineseSourceTranslations(rows)

        assertTrue(restored.map { it.zh } == listOf("你好", "世界", "继续", "哈哈哈哈", "正在翻译"))
        assertTrue(restored.map { it.text } == rows.map { it.text })
    }

    @Test
    fun rejectsMalformedAndOutOfOrderCachedCues() {
        assertFalse(SubtitleCacheValidationPolicy.hasValidTimeline(emptyList()))
        assertFalse(SubtitleCacheValidationPolicy.hasValidTimeline(listOf(Triple(-1L, 500L, "bad start"))))
        assertFalse(SubtitleCacheValidationPolicy.hasValidTimeline(listOf(Triple(500L, 500L, "empty window"))))
        assertFalse(SubtitleCacheValidationPolicy.hasValidTimeline(listOf(Triple(0L, 500L, "  "))))
        assertFalse(
            SubtitleCacheValidationPolicy.hasValidTimeline(
                listOf(Triple(1_000L, 1_500L, "later"), Triple(0L, 500L, "earlier"))
            )
        )
    }
    @Test
    fun rejectsExactDuplicateCachedCuesButAllowsDifferentTextAtSameTime() {
        assertFalse(
            SubtitleCacheValidationPolicy.hasValidTimeline(
                listOf(
                    Triple(0L, 1_000L, "duplicate"),
                    Triple(0L, 1_000L, "duplicate"),
                )
            )
        )
        assertTrue(
            SubtitleCacheValidationPolicy.hasValidTimeline(
                listOf(
                    Triple(0L, 1_000L, "speaker one"),
                    Triple(0L, 1_000L, "speaker two"),
                )
            )
        )
    }

    @Test
    fun restoresMatchingImportedCacheRowsAndLeavesIncompleteRowsForRetry() {
        val source = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 1_000L, "Hello"),
            io.translatetube.local.subs.SubLine(1, 1_000L, 2_000L, "How are you?"),
            io.translatetube.local.subs.SubLine(2, 2_000L, 3_000L, "Goodbye"),
        )
        val cached = listOf(
            SubtitleCacheValidationPolicy.CachedTranslationRow(0L, 1_000L, "Hello", "你好"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(1_000L, 2_000L, "How are you?", "正在翻译"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(2_000L, 3_000L, "Goodbye", null),
        )

        val restored = SubtitleCacheValidationPolicy.restoreTranslationsForSource(source, cached)
        val restoredLines = restored ?: throw AssertionError("Expected matching cache to restore")
        assertTrue(restoredLines.map { it.zh } == listOf("你好", null, null))
        assertTrue(restoredLines.map { it.text } == source.map { it.text })
        assertTrue(restoredLines.map { it.startMs to it.endMs } == source.map { it.startMs to it.endMs })
    }

    @Test
    fun rejectsCachedSourceEchoRowsButKeepsNamesAndValidTranslations() {
        val source = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 1_000L, "Hello"),
            io.translatetube.local.subs.SubLine(1, 1_000L, 2_000L, "Goodbye"),
            io.translatetube.local.subs.SubLine(2, 2_000L, 3_000L, "J. K. Rowling"),
        )
        val cached = listOf(
            SubtitleCacheValidationPolicy.CachedTranslationRow(0L, 1_000L, "Hello", "Hello"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(1_000L, 2_000L, "Goodbye", "再见"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(2_000L, 3_000L, "J. K. Rowling", "J. K. Rowling"),
        )

        val restored = SubtitleCacheValidationPolicy.restoreTranslationsForSource(
            source,
            cached,
            sourceLanguage = "en",
        ) ?: throw AssertionError("Expected partially valid cache to restore")

        assertEquals(listOf(null, "再见", "J. K. Rowling"), restored.map { it.zh })
    }

    @Test
    fun restoresConfirmedShortNamesButRejectsWordEchoesAndWrongNameSubstitutions() {
        val source = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 1_000L, "John"),
            io.translatetube.local.subs.SubLine(1, 1_000L, 2_000L, "Hello"),
            io.translatetube.local.subs.SubLine(2, 2_000L, 3_000L, "Sarah"),
            io.translatetube.local.subs.SubLine(3, 3_000L, 4_000L, "Taxi"),
            io.translatetube.local.subs.SubLine(4, 4_000L, 5_000L, "Goodbye"),
            io.translatetube.local.subs.SubLine(5, 5_000L, 6_000L, "John"),
            io.translatetube.local.subs.SubLine(6, 6_000L, 7_000L, "Elon Musk"),
        )
        val restored = SubtitleCacheValidationPolicy.restoreTranslationsForSource(
            source,
            listOf(
                SubtitleCacheValidationPolicy.CachedTranslationRow(0L, 1_000L, "John", "John"),
                SubtitleCacheValidationPolicy.CachedTranslationRow(1_000L, 2_000L, "Hello", "Hello"),
                SubtitleCacheValidationPolicy.CachedTranslationRow(2_000L, 3_000L, "Sarah", "Sara"),
                SubtitleCacheValidationPolicy.CachedTranslationRow(3_000L, 4_000L, "Taxi", "Taxi"),
                SubtitleCacheValidationPolicy.CachedTranslationRow(4_000L, 5_000L, "Goodbye", "Goodbye"),
                SubtitleCacheValidationPolicy.CachedTranslationRow(5_000L, 6_000L, "John", "David"),
                SubtitleCacheValidationPolicy.CachedTranslationRow(6_000L, 7_000L, "Elon Musk", "David Beckham"),
            ),
            sourceLanguage = "en",
        ) ?: throw AssertionError("Expected valid translations and confirmed short names to restore")

        assertEquals(listOf("John", null, "Sara", "Taxi", null, null, null), restored.map { it.zh })
    }
    @Test
    fun treatsCachedSourceLanguageParaphrasesAsMissingForChineseTarget() {
        val source = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 1_000L, "I think so."),
            io.translatetube.local.subs.SubLine(1, 1_000L, 2_000L, "Hello"),
            io.translatetube.local.subs.SubLine(2, 2_000L, 3_000L, "NASA"),
        )
        val cached = listOf(
            SubtitleCacheValidationPolicy.CachedTranslationRow(0L, 1_000L, "I think so.", "I believe so."),
            SubtitleCacheValidationPolicy.CachedTranslationRow(1_000L, 2_000L, "Hello", "你好"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(2_000L, 3_000L, "NASA", "NASA"),
        )

        val restored = SubtitleCacheValidationPolicy.restoreTranslationsForSource(
            source,
            cached,
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        ) ?: throw AssertionError("Expected partially valid cache to restore")

        assertEquals(listOf(null, "你好", "NASA"), restored.map { it.zh })
    }

    @Test
    fun cancellationIsCheckedWhileRestoringLargeImportedCaches() {
        val source = (0 until 700).map { index ->
            io.translatetube.local.subs.SubLine(index, index * 1_000L, (index + 1) * 1_000L, "row $index")
        }
        val cached = source.map { line ->
            SubtitleCacheValidationPolicy.CachedTranslationRow(
                line.startMs, line.endMs, line.text, "译文 ${line.index}",
            )
        }
        var checks = 0
        val error = runCatching {
            SubtitleCacheValidationPolicy.restoreTranslationsForSource(source, cached) {
                checks++
                if (checks >= 3) throw java.util.concurrent.CancellationException("test cancellation")
            }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertTrue("cancellation callback should be checked repeatedly", checks >= 3)
    }

    @Test
    fun rejectsImportedCacheWhenSubtitleTextOrTimelineDiffers() {
        val source = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 1_000L, "Hello"),
            io.translatetube.local.subs.SubLine(1, 1_000L, 2_000L, "Goodbye"),
        )
        val validRow = SubtitleCacheValidationPolicy.CachedTranslationRow(
            0L, 1_000L, "Hello", "你好",
        )
        val validSecond = SubtitleCacheValidationPolicy.CachedTranslationRow(
            1_000L, 2_000L, "Goodbye", "再见",
        )
        assertTrue(
            SubtitleCacheValidationPolicy.restoreTranslationsForSource(
                source,
                listOf(validRow, validSecond.copy(sourceText = "Different source")),
            ) == null
        )
        assertTrue(
            SubtitleCacheValidationPolicy.restoreTranslationsForSource(
                source,
                listOf(validRow.copy(endMs = 900L), validSecond),
            ) == null
        )
        assertTrue(
            SubtitleCacheValidationPolicy.restoreTranslationsForSource(
                source,
                listOf(validRow.copy(translatedText = "正在翻译"), validSecond.copy(translatedText = "")),
            ) == null
        )
    }


    @Test
    fun cacheSerializationKeepsOnlyValidatedChineseTranslations() {
        val lines = listOf(
            SubLine(0, 0L, 1_000L, "John", "John"),
            SubLine(1, 1_000L, 2_000L, "John", "David"),
            SubLine(2, 2_000L, 3_000L, "Sarah", "Sara"),
            SubLine(3, 3_000L, 4_000L, "Goodbye", "Goodbye"),
            SubLine(4, 4_000L, 5_000L, "Hello", "Hello"),
            SubLine(5, 5_000L, 6_000L, "Hello world", "你好世界"),
        )

        val sanitized = lines.map {
            SubtitleCacheValidationPolicy.sanitizeTranslationForStorage(it, "en")
        }

        assertEquals(listOf("John", null, "Sara", null, null, "你好世界"), sanitized.map { it.zh })
    }

    @Test
    fun wrappedShortNamesSurviveCacheWriteAndRestoreWithoutBeingQueuedAgain() {
        val source = listOf(SubLine(0, 0L, 1_000L, "(John):"))
        val sanitized = source.map {
            SubtitleCacheValidationPolicy.sanitizeTranslationForStorage(it.copy(zh = "“John”"), "en")
        }
        assertEquals(listOf("“John”"), sanitized.map { it.zh })

        val restored = SubtitleCacheValidationPolicy.restoreTranslationsForSource(
            source,
            listOf(
                SubtitleCacheValidationPolicy.CachedTranslationRow(
                    0L, 1_000L, "(John):", "“John”",
                ),
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        ) ?: throw AssertionError("Expected a confirmed short-name cache row to restore")

        assertEquals("“John”", restored.single().zh)
        // This is the same guard used by PlaybackService.needsSubtitleTranslation().
        assertEquals(
            "“John”",
            DubbingTranslationPolicy.usableText(
                restored.single().text,
                restored.single().zh,
                "en",
            ),
        )
    }

    @Test
    fun cacheIsRecoverableOnlyWhenAtLeastOneRowHasAValidTranslation() {
        val invalidOnly = listOf(
            SubtitleCacheValidationPolicy.CachedTranslationRow(0L, 1_000L, "John", "David"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(1_000L, 2_000L, "Goodbye", "Goodbye"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(2_000L, 3_000L, "Hello", "Hello"),
        )
        val partiallyValid = invalidOnly + SubtitleCacheValidationPolicy.CachedTranslationRow(
            3_000L, 4_000L, "Sarah", "Sara",
        )

        assertFalse(SubtitleCacheValidationPolicy.hasRecoverableTranslationRows(invalidOnly, "en"))
        assertTrue(SubtitleCacheValidationPolicy.hasRecoverableTranslationRows(partiallyValid, "en"))
    }

    @Test
    fun resumeMergeFillsOnlyMissingOrInvalidRowsAndKeepsCheckpointTranslations() {
        val checkpoint = listOf(
            SubLine(0, 0L, 1_000L, "Sarah", "Sara"),
            SubLine(1, 1_000L, 2_000L, "I agree.", null),
            SubLine(2, 2_000L, 3_000L, "Thank you.", "Thank you."),
            SubLine(3, 3_000L, 4_000L, "Where are you?", "你在哪里？"),
        )
        val cached = listOf(
            SubtitleCacheValidationPolicy.CachedTranslationRow(0L, 1_000L, "Sarah", "莎拉"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(1_000L, 2_000L, "I agree.", "我同意。"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(2_000L, 3_000L, "Thank you.", "谢谢。"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(3_000L, 4_000L, "Where are you?", "你在哪儿？"),
        )

        val merged = SubtitleCacheValidationPolicy.mergeCachedTranslationsIntoCheckpoint(
            checkpointLines = checkpoint,
            cachedRows = cached,
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertEquals(listOf("Sara", "我同意。", "谢谢。", "你在哪里？"), merged.map { it.zh })
    }

    @Test
    fun resumeMergeRejectsCacheFromDifferentSourceTimelineAndKeepsCheckpoint() {
        val checkpoint = listOf(
            SubLine(0, 0L, 1_000L, "Hello", null),
            SubLine(1, 1_000L, 2_000L, "Goodbye", "再见"),
        )
        val mismatchedCache = listOf(
            SubtitleCacheValidationPolicy.CachedTranslationRow(0L, 1_000L, "Different source", "你好"),
            SubtitleCacheValidationPolicy.CachedTranslationRow(1_000L, 2_000L, "Goodbye", "Farewell"),
        )

        val merged = SubtitleCacheValidationPolicy.mergeCachedTranslationsIntoCheckpoint(
            checkpointLines = checkpoint,
            cachedRows = mismatchedCache,
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertEquals(listOf(null, "再见"), merged.map { it.zh })
    }

}