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
}
