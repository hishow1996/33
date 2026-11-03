package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRecognitionJournalTaskPolicyTest {
    @Test
    fun onlyTheCurrentTaskCanPersistProgress() {
        assertTrue(SpeakerRecognitionJournalTaskPolicy.acceptsUpdate(12L, 12L))
        assertFalse(SpeakerRecognitionJournalTaskPolicy.acceptsUpdate(13L, 12L))
        assertFalse(SpeakerRecognitionJournalTaskPolicy.acceptsUpdate(12L, 13L))
    }

    @Test
    fun anUnclaimedOrCompletedTaskCannotRecreateAJournal() {
        assertFalse(SpeakerRecognitionJournalTaskPolicy.acceptsUpdate(null, 1L))
        assertFalse(SpeakerRecognitionJournalTaskPolicy.acceptsUpdate(1L, 0L))
    }
}
