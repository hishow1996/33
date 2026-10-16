package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCheckpointPolicyTest {
    @Test
    fun persistsFirstPeriodicAndFinalBatches() {
        val saved = (1..10).filter {
            TranslationCheckpointPolicy.shouldPersistCheckpoint(it, totalBatches = 10)
        }
        assertEquals(listOf(1, 3, 6, 9, 10), saved)
    }

    @Test
    fun alwaysPersistsFinalBatchEvenWhenItMissesInterval() {
        assertTrue(TranslationCheckpointPolicy.shouldPersistCheckpoint(4, totalBatches = 4))
        assertTrue(TranslationCheckpointPolicy.shouldPersistCheckpoint(5, totalBatches = 5))
    }

    @Test
    fun handlesSmallRunsWithoutLosingFinalCheckpoint() {
        assertEquals(
            listOf(1, 2),
            (1..2).filter { TranslationCheckpointPolicy.shouldPersistCheckpoint(it, 2) },
        )
        assertEquals(
            listOf(1),
            (1..1).filter { TranslationCheckpointPolicy.shouldPersistCheckpoint(it, 1) },
        )
    }

    @Test
    fun rejectsInvalidCountersAndIntervals() {
        assertFalse(TranslationCheckpointPolicy.shouldPersistCheckpoint(0, 10))
        assertFalse(TranslationCheckpointPolicy.shouldPersistCheckpoint(11, 10))
        assertFalse(TranslationCheckpointPolicy.shouldPersistCheckpoint(1, 0))
        assertFalse(TranslationCheckpointPolicy.shouldPersistCheckpoint(1, 10, interval = 0))
    }

    @Test
    fun customIntervalChangesOnlyPeriodicFlushes() {
        assertEquals(
            listOf(1, 2, 4, 5),
            (1..5).filter {
                TranslationCheckpointPolicy.shouldPersistCheckpoint(it, totalBatches = 5, interval = 2)
            },
        )
    }
}
