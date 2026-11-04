package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingProcessingResumeWritePolicyTest {
    @Test
    fun acceptsOnlyTheCurrentSessionAndGeneration() {
        assertEquals(
            true,
            PendingProcessingResumeWritePolicy.shouldWrite(
                activeSessionKey = "youtube:abcdefghijk",
                activeGeneration = 12L,
                expectedSessionKey = "youtube:abcdefghijk",
                expectedGeneration = 12L,
            ),
        )
    }

    @Test
    fun rejectsAnObsoleteGenerationEvenWhenTheVideoKeyIsTheSame() {
        assertEquals(
            false,
            PendingProcessingResumeWritePolicy.shouldWrite(
                activeSessionKey = "youtube:abcdefghijk",
                activeGeneration = 13L,
                expectedSessionKey = "youtube:abcdefghijk",
                expectedGeneration = 12L,
            ),
        )
    }

    @Test
    fun rejectsDifferentKeysAndInvalidGenerations() {
        assertEquals(
            false,
            PendingProcessingResumeWritePolicy.shouldWrite(
                activeSessionKey = "remote:https://cdn.example/video.m3u8",
                activeGeneration = 12L,
                expectedSessionKey = "youtube:abcdefghijk",
                expectedGeneration = 12L,
            ),
        )
        assertEquals(
            false,
            PendingProcessingResumeWritePolicy.shouldWrite(
                activeSessionKey = "youtube:abcdefghijk",
                activeGeneration = 0L,
                expectedSessionKey = "youtube:abcdefghijk",
                expectedGeneration = 0L,
            ),
        )
    }
}
