package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTimelinePolicyTest {
    @Test
    fun sortsOutOfOrderProviderCuesByTheirVideoTimestamps() {
        val normalized = TranscriptTimelinePolicy.normalize(
            listOf(
                Triple(2_000L, 2_500L, "second"),
                Triple(500L, 900L, "first"),
                Triple(1_000L, 1_400L, "middle"),
            ),
            audioDurationMs = 3_000L,
        )

        assertEquals(
            listOf(
                Triple(500L, 900L, "first"),
                Triple(1_000L, 1_400L, "middle"),
                Triple(2_000L, 2_500L, "second"),
            ),
            normalized,
        )
        assertTrue(normalized.zipWithNext().all { (left, right) -> left.first <= right.first })
    }

    @Test
    fun clampsCueEndsToSubmittedAudioAndDropsCuesBeyondItsEnd() {
        assertEquals(
            listOf(
                Triple(9_500L, 10_000L, "last valid cue"),
            ),
            TranscriptTimelinePolicy.normalize(
                listOf(
                    Triple(9_500L, 12_000L, "last valid cue"),
                    Triple(10_000L, 10_500L, "outside audio"),
                ),
                audioDurationMs = 10_000L,
            ),
        )
    }

    @Test
    fun rejectsInvalidAndBlankIntervalsWithoutChangingValidTimestamps() {
        assertEquals(
            listOf(Triple(100L, 400L, "keep exact time")),
            TranscriptTimelinePolicy.normalize(
                listOf(
                    Triple(-1L, 100L, "negative"),
                    Triple(50L, 50L, "zero duration"),
                    Triple(100L, 400L, " keep exact time "),
                    Triple(500L, 600L, "   "),
                ),
                audioDurationMs = 1_000L,
            ),
        )
    }

    @Test
    fun clipsOverlappingTranscriptCuesAtTheNextLaterCueStart() {
        val normalized = TranscriptTimelinePolicy.normalize(
            listOf(
                Triple(100L, 1_000L, "first phrase"),
                Triple(500L, 800L, "second phrase"),
                Triple(900L, 1_200L, "third phrase"),
            ),
            audioDurationMs = 2_000L,
        )

        assertEquals(
            listOf(
                Triple(100L, 500L, "first phrase"),
                Triple(500L, 800L, "second phrase"),
                Triple(900L, 1_200L, "third phrase"),
            ),
            normalized,
        )
        assertTrue(normalized.zipWithNext().all { (left, right) -> left.second <= right.first })
    }

    @Test
    fun clipsToNextDistinctStartAndKeepsSameStartCaptions() {
        val normalized = TranscriptTimelinePolicy.normalize(
            listOf(
                Triple(100L, 900L, "first"),
                Triple(100L, 700L, "same-start alternate"),
                Triple(500L, 800L, "next"),
            ),
            audioDurationMs = 1_000L,
        )

        assertEquals(
            listOf(
                Triple(100L, 500L, "first"),
                Triple(100L, 500L, "same-start alternate"),
                Triple(500L, 800L, "next"),
            ),
            normalized,
        )
    }

    @Test
    fun leavesTimesIntactWhenAudioDurationIsUnavailable() {
        val cues = listOf(Triple(2_000L, 3_000L, "second"), Triple(0L, 1_000L, "first"))
        assertEquals(
            listOf(Triple(0L, 1_000L, "first"), Triple(2_000L, 3_000L, "second")),
            TranscriptTimelinePolicy.normalize(cues, audioDurationMs = 0L),
        )
    }
    @Test
    fun removesOnlyExactDuplicateCuesWithoutMergingLegitimateRepeatedSpeech() {
        val normalized = TranscriptTimelinePolicy.normalize(
            listOf(
                Triple(0L, 500L, "hello"),
                Triple(0L, 500L, "hello"),
                Triple(500L, 900L, "hello"),
                Triple(900L, 1_200L, "world"),
            ),
            audioDurationMs = 2_000L,
        )
        assertEquals(
            listOf(
                Triple(0L, 500L, "hello"),
                Triple(500L, 900L, "hello"),
                Triple(900L, 1_200L, "world"),
            ),
            normalized,
        )
    }

}
