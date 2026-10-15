package io.translatetube.local.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerModelDownloadPolicyTest {
    @Test
    fun onlyFullModelResponsesAreAccepted() {
        assertTrue(SpeakerModelDownloadPolicy.isFullResponse(200))
        assertFalse(SpeakerModelDownloadPolicy.isFullResponse(206))
        assertFalse(SpeakerModelDownloadPolicy.isFullResponse(204))
        assertFalse(SpeakerModelDownloadPolicy.isFullResponse(302))
    }

    @Test
    fun contentLengthAcceptsOnlyUnknownOrPositiveBoundedLengths() {
        assertTrue(SpeakerModelDownloadPolicy.isValidExpectedLength(-1L, 1_000L))
        assertTrue(SpeakerModelDownloadPolicy.isValidExpectedLength(1_000L, 1_000L))
        assertFalse(SpeakerModelDownloadPolicy.isValidExpectedLength(-2L, 1_000L))
        assertFalse(SpeakerModelDownloadPolicy.isValidExpectedLength(0L, 1_000L))
        assertFalse(SpeakerModelDownloadPolicy.isValidExpectedLength(1_001L, 1_000L))
    }

    @Test
    fun incompleteOrOversizedPayloadsCannotBeInstalled() {
        assertTrue(SpeakerModelDownloadPolicy.isCompletePayload(100L, 100L, 200L))
        assertTrue(SpeakerModelDownloadPolicy.isCompletePayload(-1L, 100L, 200L))
        assertFalse(SpeakerModelDownloadPolicy.isCompletePayload(100L, 99L, 200L))
        assertFalse(SpeakerModelDownloadPolicy.isCompletePayload(-1L, 201L, 200L))
        assertFalse(SpeakerModelDownloadPolicy.isCompletePayload(0L, 100L, 200L))
    }

    @Test
    fun extractedArchiveEntryMustMatchDeclaredSizeExactly() {
        assertTrue(SpeakerModelDownloadPolicy.isCompleteArchiveEntry(2_000_000L, 2_000_000L, 3_000_000L))
        assertFalse(SpeakerModelDownloadPolicy.isCompleteArchiveEntry(2_000_000L, 1_500_000L, 3_000_000L))
        assertFalse(SpeakerModelDownloadPolicy.isCompleteArchiveEntry(31_000_000L, 31_000_000L, 30_000_000L))
        assertFalse(SpeakerModelDownloadPolicy.isCompleteArchiveEntry(-1L, 1_000_000L, 30_000_000L))
    }
}
