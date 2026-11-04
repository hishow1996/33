package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InProgressProcessingCheckpointStoragePolicyTest {
    @Test
    fun identicalPayloadSkipsDiskWriteOnlyWhenFileStampStillMatches() {
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isUnchanged(
                cachedFingerprint = "same",
                requestedFingerprint = "same",
                fileExists = true,
                cachedLength = 120L,
                currentLength = 120L,
                cachedLastModified = 10L,
                currentLastModified = 10L,
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.isUnchanged(
                cachedFingerprint = "same",
                requestedFingerprint = "same",
                fileExists = false,
                cachedLength = 120L,
                currentLength = 120L,
                cachedLastModified = 10L,
                currentLastModified = 10L,
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.isUnchanged(
                cachedFingerprint = "same",
                requestedFingerprint = "different",
                fileExists = true,
                cachedLength = 120L,
                currentLength = 120L,
                cachedLastModified = 10L,
                currentLastModified = 10L,
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.isUnchanged(
                cachedFingerprint = "same",
                requestedFingerprint = "same",
                fileExists = true,
                cachedLength = 120L,
                currentLength = 121L,
                cachedLastModified = 10L,
                currentLastModified = 10L,
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.isUnchanged(
                cachedFingerprint = "same",
                requestedFingerprint = "same",
                fileExists = true,
                cachedLength = 120L,
                currentLength = 120L,
                cachedLastModified = 10L,
                currentLastModified = 11L,
            ),
        )
    }

    @Test
    fun invalidAndExpiredTimestampsAreRejectedWithClockSkewTolerance() {
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isExpired(
                savedAt = 0L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
            ),
        )
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isExpired(
                savedAt = 800L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.isExpired(
                savedAt = 950L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.isExpired(
                savedAt = 1_005L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
            ),
        )
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isExpired(
                savedAt = 1_020L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
            ),
        )
    }

    @Test
    fun invalidFutureClockSkewExpiredFilesAndSizeLimitsAreRejected() {
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isExpiredFile(
                lastModifiedMs = 0L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
                fileBytes = 1L,
                maxFileBytes = 100L,
            ),
        )
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isExpiredFile(
                lastModifiedMs = 1_020L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
                fileBytes = 1L,
                maxFileBytes = 100L,
            ),
        )
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isExpiredFile(
                lastModifiedMs = 950L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
                fileBytes = 101L,
                maxFileBytes = 100L,
            ),
        )
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.isExpiredFile(
                lastModifiedMs = 800L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
                fileBytes = 50L,
                maxFileBytes = 100L,
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.isExpiredFile(
                lastModifiedMs = 1_005L,
                nowMs = 1_000L,
                maxAgeMs = 100L,
                maxFutureSkewMs = 10L,
                fileBytes = 50L,
                maxFileBytes = 100L,
            ),
        )
    }

    @Test
    fun onlyRejectedOrFailedWritesNeedDiagnostics() {
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.shouldReportSaveOutcome(
                InProgressCheckpointSaveStatus.SAVED,
                "written",
            ),
        )
        assertFalse(
            InProgressProcessingCheckpointStoragePolicy.shouldReportSaveOutcome(
                InProgressCheckpointSaveStatus.UNCHANGED,
                "identical_content",
            ),
        )
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.shouldReportSaveOutcome(
                InProgressCheckpointSaveStatus.REJECTED,
                "uncompressed_size_limit",
            ),
        )
        assertTrue(
            InProgressProcessingCheckpointStoragePolicy.shouldReportSaveOutcome(
                InProgressCheckpointSaveStatus.FAILED,
                "checkpoint_write_failed",
            ),
        )
    }
}
