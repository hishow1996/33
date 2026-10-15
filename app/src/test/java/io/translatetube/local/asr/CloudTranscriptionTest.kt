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
    fun invalidDurationProducesNoCuesInsteadOfThrowing() {
        assertTrue(CloudTranscription.groupWords(
            listOf(CloudTranscription.TimedWord("hello", 0L, 100L)),
            durationMs = 0L,
        ).isEmpty())
        assertTrue(CloudTranscription.groupWords(emptyList(), durationMs = -1L).isEmpty())
    }

    @Test
    fun emptyWordListProducesNoCues() {
        assertTrue(CloudTranscription.groupWords(emptyList(), durationMs = 1_000L).isEmpty())
    }

    @Test
    fun kawaChunksAreBoundedContiguousAndCoverTheWholePcm() {
        val chunkLimit = 8L * 1024L * 1024L
        val pcmBytes = chunkLimit * 2L + 12_344L
        val ranges = CloudTranscription.kawaChunkRanges(pcmBytes)

        assertEquals(listOf(0L, chunkLimit, chunkLimit * 2L), ranges.map { it.first })
        assertEquals(listOf(chunkLimit, chunkLimit, 12_344L), ranges.map { it.second })
        assertTrue(ranges.all { (offset, size) -> offset >= 0L && size in 2L..chunkLimit && size % 2L == 0L })
        assertEquals(pcmBytes, ranges.sumOf { it.second })
        assertTrue(CloudTranscription.kawaChunkRanges(0L).isEmpty())
        assertTrue(CloudTranscription.kawaChunkRanges(3L).isEmpty())
    }
}
