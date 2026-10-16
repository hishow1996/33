package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperSubtitleTimelinePolicyTest {
    @Test
    fun mapsTokenTimingsOntoTheMediaClockAndSplitsSentences() {
        val cues = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
            tokens = listOf("Hello", " world", ".", " Next", " sentence", "!"),
            timestampsSeconds = listOf(0.0f, 0.35f, 0.8f, 1.4f, 1.7f, 2.3f),
            durationsSeconds = listOf(0.35f, 0.45f, 0.12f, 0.3f, 0.6f, 0.2f),
            segmentStartMs = 1_000L,
            segmentEndMs = 4_000L,
            expectedText = "Hello world. Next sentence!",
        )

        assertEquals(2, cues.size)
        assertEquals(Triple(1_000L, 1_920L, "Hello world."), cues[0])
        assertEquals(Triple(2_400L, 3_500L, "Next sentence!"), cues[1])
    }

    @Test
    fun keepsChineseTextAndUsesTokenTimesWithinTheVadSegment() {
        val cues = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
            tokens = listOf("你好", "，", "世界", "。"),
            timestampsSeconds = listOf(0.0f, 0.25f, 0.4f, 0.9f),
            durationsSeconds = listOf(0.25f, 0.15f, 0.5f, 0.2f),
            segmentStartMs = 5_000L,
            segmentEndMs = 6_500L,
            expectedText = "你好，世界。",
        )

        assertEquals(1, cues.size)
        assertEquals(Triple(5_000L, 6_100L, "你好，世界。"), cues.single())
    }

    @Test
    fun acceptsWhitespaceDifferencesButStillRejectsActualTextMismatch() {
        val tokens = listOf("Hello  ", " world")
        val timestamps = listOf(0.0f, 0.5f)
        val durations = listOf(0.4f, 0.5f)
        val whitespaceVariant = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
            tokens = tokens,
            timestampsSeconds = timestamps,
            durationsSeconds = durations,
            segmentStartMs = 0L,
            segmentEndMs = 2_000L,
            expectedText = "Hello world",
        )
        assertTrue(whitespaceVariant.isNotEmpty())
        assertEquals("Hello world", whitespaceVariant.single().third)

        assertTrue(
            WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                tokens = listOf("Hello ", "world"),
                timestampsSeconds = listOf(0.0f, 0.5f),
                durationsSeconds = listOf(0.4f, 0.5f),
                segmentStartMs = 0L,
                segmentEndMs = 2_000L,
                expectedText = "Hello there",
            ).isEmpty()
        )
    }

    @Test
    fun refusesMissingMalformedOrOutOfRangeTimingsInsteadOfInventingAlignment() {
        assertTrue(
            WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                tokens = listOf("one", " two"),
                timestampsSeconds = emptyList(),
                durationsSeconds = emptyList(),
                segmentStartMs = 0L,
                segmentEndMs = 1_000L,
                expectedText = "one two",
            ).isEmpty()
        )
        assertTrue(
            WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                tokens = listOf("one", " two"),
                timestampsSeconds = listOf(0.5f, 0.1f),
                durationsSeconds = listOf(0.2f, 0.2f),
                segmentStartMs = 0L,
                segmentEndMs = 1_000L,
                expectedText = "one two",
            ).isEmpty()
        )
        assertTrue(
            WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                tokens = listOf("one", " two"),
                timestampsSeconds = listOf(0.0f, 2.0f),
                durationsSeconds = listOf(0.3f, 0.3f),
                segmentStartMs = 100L,
                segmentEndMs = 1_000L,
                expectedText = "one two",
            ).isEmpty()
        )
    }

    @Test
    fun longPhraseBreaksIntoCuesUsingRealTokenStarts() {
        val tokens = listOf("This ", "is ", "the ", "first ", "part ", "of ", "this ", "sentence")
        val timestamps = listOf(0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 3.7f, 4.0f, 4.4f)
        val durations = List(tokens.size) { 0.4f }
        val cues = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
            tokens = tokens,
            timestampsSeconds = timestamps,
            durationsSeconds = durations,
            segmentStartMs = 2_000L,
            segmentEndMs = 8_000L,
            expectedText = tokens.joinToString(""),
        )

        assertEquals(2, cues.size)
        assertEquals(2_000L, cues[0].first)
        assertEquals(5_700L, cues[1].first)
        assertTrue(cues.zipWithNext().all { (left, right) -> left.first < right.first })
    }
    @Test
    fun createsChronologicalCuesForLargeTokenLists() {
        val tokens = List(3_000) { "a" }
        val timestamps = List(3_000) { it * 0.05f }
        val durations = List(3_000) { 0.04f }
        val cues = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
            tokens = tokens,
            timestampsSeconds = timestamps,
            durationsSeconds = durations,
            segmentStartMs = 0L,
            segmentEndMs = 200_000L,
            expectedText = tokens.joinToString(""),
        )
        assertTrue(cues.isNotEmpty())
        assertTrue(cues.all { it.first >= 0L && it.second > it.first && it.second <= 200_000L })
        assertTrue(cues.zipWithNext().all { (left, right) -> left.first <= right.first })
    }

    @Test
    fun rejectsRepeatedTimestampsWhenNoUsefulDurationsExist() {
        assertTrue(
            WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                tokens = listOf("one", " two"),
                timestampsSeconds = listOf(0.5f, 0.5f),
                durationsSeconds = listOf(0f, Float.NaN),
                segmentStartMs = 0L,
                segmentEndMs = 2_000L,
                expectedText = "one two",
            ).isEmpty()
        )
    }

    @Test
    fun clampsHugeFiniteTokenDurationWithoutLongOverflow() {
        val cues = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
            tokens = listOf("hello"),
            timestampsSeconds = listOf(0.0f),
            durationsSeconds = listOf(Float.MAX_VALUE),
            segmentStartMs = 1_000L,
            segmentEndMs = Long.MAX_VALUE,
            expectedText = "hello",
        )

        assertEquals(1, cues.size)
        assertEquals(1_000L, cues.single().first)
        assertEquals(Long.MAX_VALUE, cues.single().second)
        assertEquals("hello", cues.single().third)
    }

}