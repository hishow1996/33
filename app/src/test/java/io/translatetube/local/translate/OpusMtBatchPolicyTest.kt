package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class OpusMtBatchPolicyTest {
    private val mb = 1024L * 1024L

    @Test
    fun reducesBatchSizeAsAvailableMemoryFalls() {
        assertEquals(
            OpusMtBatchPolicy.Limits(2, 1_024),
            OpusMtBatchPolicy.resolve(768L * mb),
        )
        assertEquals(
            OpusMtBatchPolicy.Limits(4, 2_048),
            OpusMtBatchPolicy.resolve(1_024L * mb),
        )
        assertEquals(
            OpusMtBatchPolicy.Limits(8, 4_096),
            OpusMtBatchPolicy.resolve(1_536L * mb),
        )
    }

    @Test
    fun neverExceedsExampleOrAggregateCharacterBudget() {
        val lengths = listOf(300, 400, 500, 600, 700)
        val limits = OpusMtBatchPolicy.Limits(maxExamples = 4, maxCharacters = 1_000)

        val firstEnd = OpusMtBatchPolicy.nextBatchEndIndex(lengths, 0, limits)
        val secondEnd = OpusMtBatchPolicy.nextBatchEndIndex(lengths, firstEnd, limits)

        assertEquals(2, firstEnd)
        assertEquals(3, secondEnd)
    }

    @Test
    fun isolatesOneOversizedCueWithoutTruncatingOrStalling() {
        val lengths = listOf(200, 2_500, 100)
        val limits = OpusMtBatchPolicy.Limits(maxExamples = 8, maxCharacters = 1_000)

        val firstEnd = OpusMtBatchPolicy.nextBatchEndIndex(lengths, 0, limits)
        val longCueEnd = OpusMtBatchPolicy.nextBatchEndIndex(lengths, firstEnd, limits)
        val finalEnd = OpusMtBatchPolicy.nextBatchEndIndex(lengths, longCueEnd, limits)

        assertEquals(1, firstEnd)
        assertEquals(2, longCueEnd)
        assertEquals(3, finalEnd)
    }

    @Test
    fun emptyAndNegativeLengthsRemainSafe() {
        val limits = OpusMtBatchPolicy.Limits(maxExamples = 2, maxCharacters = 100)
        assertEquals(0, OpusMtBatchPolicy.nextBatchEndIndex(emptyList(), 0, limits))
        assertEquals(2, OpusMtBatchPolicy.nextBatchEndIndex(listOf(-10, 30, 40), 0, limits))
    }
}
