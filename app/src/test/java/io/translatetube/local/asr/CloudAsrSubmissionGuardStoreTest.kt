package io.translatetube.local.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAsrSubmissionGuardStoreTest {
    @Test
    fun requestMarkerSurvivesProcessStyleReopenUntilExplicitlyCleared() {
        val directory = createTempDir(prefix = "cloud-asr-pending")
        try {
            val marker = CloudAsrSubmissionGuardStore.fileFor(directory, "elevenlabs", "remote-session|source|config", "a".repeat(64))
            assertFalse(CloudAsrSubmissionGuardStore.hasPendingSubmission(marker))
            CloudAsrSubmissionGuardStore.markInFlight(marker)
            assertTrue(CloudAsrSubmissionGuardStore.hasPendingSubmission(File(directory, marker.name)))
            CloudAsrSubmissionGuardStore.clear(marker)
            assertFalse(CloudAsrSubmissionGuardStore.hasPendingSubmission(marker))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun markerIdentityStaysStableAcrossConfigurationAndUploadChanges() {
        val directory = createTempDir(prefix = "cloud-asr-pending-identity")
        try {
            val sameSource = CloudAsrSubmissionGuardStore.fileFor(directory, "cartesia", "remote-session|media")
            val changedSource = CloudAsrSubmissionGuardStore.fileFor(directory, "cartesia", "remote-session|other-media")
            val changedProvider = CloudAsrSubmissionGuardStore.fileFor(directory, "elevenlabs", "remote-session|media")
            // ASR language, API key and temporary upload encoding are deliberately excluded.
            assertEquals(sameSource, CloudAsrSubmissionGuardStore.fileFor(directory, "cartesia", "remote-session|media"))
            assertFalse(sameSource.name == changedSource.name)
            assertFalse(sameSource.name == changedProvider.name)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun markerDoesNotExposeSourceIdentityOrAudioDigest() {
        val directory = createTempDir(prefix = "cloud-asr-pending-private")
        try {
            val identity = "https://example.invalid/private/video?token=secret"
            val digest = "d".repeat(64)
            val marker = CloudAsrSubmissionGuardStore.fileFor(directory, "elevenlabs", identity)
            assertFalse(marker.name.contains("example"))
            assertFalse(marker.name.contains("secret"))
            assertFalse(marker.name.contains(digest))
        } finally { directory.deleteRecursively() }
    }
}
