package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrLanguageVotePolicyTest {
    @Test
    fun noVotesGivesEmptyLanguage() {
        assertEquals("", AsrLanguageVotePolicy().best())
    }

    @Test
    fun longSegmentsOutvoteAShortMisdetectedLastSegment() {
        val vote = AsrLanguageVotePolicy()
        vote.add("en", 12_000L)
        vote.add("en", 9_000L)
        vote.add("nn", 800L) // 结尾一句很短的话被认错
        assertEquals("en", vote.best())
    }

    @Test
    fun blankLanguageAndNonPositiveWeightAreIgnored() {
        val vote = AsrLanguageVotePolicy()
        vote.add("", 5_000L)
        vote.add(null, 5_000L)
        vote.add("  ", 5_000L)
        vote.add("ja", 0L)
        vote.add("ja", -10L)
        assertEquals("", vote.best())
        vote.add(" ja ", 1_000L)
        assertEquals("ja", vote.best())
    }

    @Test
    fun tiesPreferTheMostRecentLanguage() {
        val vote = AsrLanguageVotePolicy()
        vote.add("en", 3_000L)
        vote.add("es", 3_000L)
        assertEquals("es", vote.best())
        vote.add("en", 0L)
        assertEquals("es", vote.best())
    }

    @Test
    fun earlierWinnerStaysAheadOfALaterSmallerVote() {
        val vote = AsrLanguageVotePolicy()
        vote.add("zh", 10_000L)
        vote.add("en", 4_000L)
        assertEquals("zh", vote.best())
    }

    @Test
    fun clearStartsAFreshVideo() {
        val vote = AsrLanguageVotePolicy()
        vote.add("en", 10_000L)
        vote.clear()
        assertEquals("", vote.best())
        vote.add("ko", 1_000L)
        assertEquals("ko", vote.best())
    }

    @Test
    fun hugeWeightsDoNotOverflow() {
        val vote = AsrLanguageVotePolicy()
        vote.add("en", Long.MAX_VALUE - 1L)
        vote.add("en", 10L)
        vote.add("fr", 5L)
        assertEquals("en", vote.best())
    }
}
