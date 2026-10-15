package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTranscriptionTest {
    @Test
    fun groupsOutOfOrderProviderWordsByTimestamp() {
        val cues = CloudTranscription.groupWords(
            listOf(
                CloudTranscription.TimedWord("world", 900L, 1_300L),
                CloudTranscription.TimedWord("Hello", 100L, 500L),
            ),
            durationMs = 2_000L,
        )

        assertEquals(1, cues.size)
        assertEquals(100L, cues.single().first)
        assertEquals(1_300L, cues.single().second)
        assertEquals("Hello world", cues.single().third)
    }

    @Test
    fun keepsTimelineWithinMediaDuration() {
        val cues = CloudTranscription.groupWords(
            listOf(
                CloudTranscription.TimedWord("ending", 850L, 1_000L),
                CloudTranscription.TimedWord("near", 700L, 820L),
            ),
            durationMs = 1_000L,
        )

        assertTrue(cues.all { it.first >= 0L && it.second <= 1_000L && it.second > it.first })
        assertEquals("near ending", cues.single().third)
    }

    @Test
    fun emptyWordListProducesNoCues() {
        assertTrue(CloudTranscription.groupWords(emptyList(), durationMs = 1_000L).isEmpty())
    }
}
