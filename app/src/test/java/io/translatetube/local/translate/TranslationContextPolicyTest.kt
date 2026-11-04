package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationContextPolicyTest {
    @Test
    fun returnsOnlyTheNearestUsableRowsInOriginalOrder() {
        val usable = setOf(0, 2, 4, 5, 7, 8)
        val result = TranslationContextPolicy.mostRecentIndicesBefore(
            size = 10,
            beforeIndex = 9,
            maxRows = 3,
            isUsable = { it in usable },
        )

        assertEquals(listOf(5, 7, 8), result)
    }

    @Test
    fun stopsScanningAfterEnoughRowsAreFound() {
        val visited = mutableListOf<Int>()
        val result = TranslationContextPolicy.mostRecentIndicesBefore(
            size = 20_000,
            beforeIndex = 19_999,
            maxRows = 3,
        ) {
            visited += it
            true
        }

        assertEquals(listOf(19_996, 19_997, 19_998), result)
        assertEquals(listOf(19_998, 19_997, 19_996), visited)
    }

    @Test
    fun handlesEmptyRangesAndNonPositiveLimits() {
        assertTrue(TranslationContextPolicy.mostRecentIndicesBefore(100, 0) { true }.isEmpty())
        assertTrue(TranslationContextPolicy.mostRecentIndicesBefore(100, 50, 0) { true }.isEmpty())
        assertTrue(TranslationContextPolicy.mostRecentIndicesBefore(0, 0) { true }.isEmpty())
    }

@Test
    fun longBackwardScanCanBeCancelledCooperatively() {
        var checks = 0
        val error = runCatching {
            TranslationContextPolicy.mostRecentIndicesBefore(
                size = 100_000,
                beforeIndex = 100_000,
                maxRows = 3,
                checkActive = {
                    checks++
                    if (checks == 3) throw java.util.concurrent.CancellationException("cancel context scan")
                },
                isUsable = { false },
            )
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(3, checks)
    }

    @Test
    fun cancellationIsCheckedBeforeReturningFoundContextRows() {
        var checks = 0
        val error = runCatching {
            TranslationContextPolicy.mostRecentIndicesBefore(
                size = 10,
                beforeIndex = 10,
                maxRows = 3,
                checkActive = {
                    checks++
                    if (checks == 2) throw java.util.concurrent.CancellationException("cancel context result")
                },
                isUsable = { true },
            )
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }

    @Test
    fun oversizedOrClampedIndexDoesNotVisitRowsOutsideTheRequestedPrefix() {
        val visited = mutableListOf<Int>()
        val result = TranslationContextPolicy.mostRecentIndicesBefore(
            size = 5,
            beforeIndex = 99,
            maxRows = 2,
            isUsable = { visited += it; true },
        )
        assertEquals(listOf(3, 4), result)
        assertEquals(listOf(4, 3), visited)
    }

    
    @Test
    fun buildsAlignedRecentContextPairsInChronologicalOrderAndHonorsRetryOverrides() {
        val sources = listOf("第零条", "第一条", "第二条", "第三条", "当前条")
        val cached = listOf("旧译零", "旧译一", "旧译二", "旧译三", "")
        val retryOverrides = mapOf(2 to "刚完成的第二条译文")
        val context = TranslationContextPolicy.mostRecentPairsBefore(
            size = sources.size,
            beforeIndex = 4,
            maxRows = 3,
            sourceAt = { sources[it] },
            translationAt = { retryOverrides[it] ?: cached[it] },
            isUsable = { _, translation -> translation.isNotBlank() },
        )

        assertEquals(listOf("第一条", "第二条", "第三条"), context?.first)
        assertEquals(listOf("旧译一", "刚完成的第二条译文", "旧译三"), context?.second)
    }

    @Test
    fun contextPairsNeverIncludeTheCueBeingRetriedOrAnyFollowingCue() {
        val sources = listOf("前一句", "当前句", "后一句")
        val translations = listOf("前译", "当前译", "后译")
        val context = TranslationContextPolicy.mostRecentPairsBefore(
            size = sources.size,
            beforeIndex = 1,
            sourceAt = { sources[it] },
            translationAt = { translations[it] },
        )

        assertEquals(listOf("前一句"), context?.first)
        assertEquals(listOf("前译"), context?.second)
    }

    @Test
    fun contextPairScanCanBeCancelledCooperatively() {
        var checks = 0
        val error = runCatching {
            TranslationContextPolicy.mostRecentPairsBefore(
                size = 100_000,
                beforeIndex = 100_000,
                maxRows = 3,
                checkActive = {
                    checks++
                    if (checks == 2) throw java.util.concurrent.CancellationException("cancel context pair scan")
                },
                sourceAt = { "source" },
                translationAt = { "translation" },
            )
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(2, checks)
    }

}
