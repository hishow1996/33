package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackSelectionPolicyTest {
    @Test
    fun defaultTrackWinsOverHigherChannelCountAlternative() {
        val chosen = AudioTrackSelectionPolicy.choose(
            listOf(
                AudioTrackSelectionPolicy.Candidate(index = 0, isDefault = false, channelCount = 6),
                AudioTrackSelectionPolicy.Candidate(index = 1, isDefault = true, channelCount = 2),
            )
        )

        assertEquals(1, checkNotNull(chosen))
    }

    @Test
    fun channelCountIsOnlyUsedWhenDefaultMetadataIsEqual() {
        val chosen = AudioTrackSelectionPolicy.choose(
            listOf(
                AudioTrackSelectionPolicy.Candidate(index = 0, isDefault = false, channelCount = 1),
                AudioTrackSelectionPolicy.Candidate(index = 1, isDefault = false, channelCount = 2),
            )
        )

        assertEquals(1, checkNotNull(chosen))
    }

    @Test
    fun originalTrackOrderBreaksRemainingTies() {
        val chosen = AudioTrackSelectionPolicy.choose(
            listOf(
                AudioTrackSelectionPolicy.Candidate(index = 3, isDefault = false, channelCount = 2),
                AudioTrackSelectionPolicy.Candidate(index = 1, isDefault = false, channelCount = 2),
            )
        )

        assertEquals(1, checkNotNull(chosen))
    }

    @Test
    fun invalidAndEmptyCandidatesAreIgnored() {
        assertNull(
            AudioTrackSelectionPolicy.choose(
                listOf(AudioTrackSelectionPolicy.Candidate(index = -1, isDefault = true, channelCount = 2))
            )
        )
        assertNull(AudioTrackSelectionPolicy.choose(emptyList()))
    }
}
