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

}
