package io.translatetube.local.player

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

}