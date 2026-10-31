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
    fun markerIdentitySeparatesProviderConfigurationAndAudio() {
        val directory = createTempDir(prefix = "cloud-asr-pending-identity")
        try {
            val same = CloudAsrSubmissionGuardStore.fileFor(directory, "cartesia", "source|config-a", "b".repeat(64))
            val changedConfig = CloudAsrSubmissionGuardStore.fileFor(directory, "cartesia", "source|config-b", "b".repeat(64))
            val changedAudio = CloudAsrSubmissionGuardStore.fileFor(directory, "cartesia", "source|config-a", "c".repeat(64))
            val changedProvider = CloudAsrSubmissionGuardStore.fileFor(directory, "elevenlabs", "source|config-a", "b".repeat(64))
            assertEquals(same, CloudAsrSubmissionGuardStore.fileFor(directory, "cartesia", "source|config-a", "b".repeat(64)))
            assertFalse(same.name == changedConfig.name)
            assertFalse(same.name == changedAudio.name)
            assertFalse(same.name == changedProvider.name)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun markerDoesNotExposeSourceIdentityOrAudioDigest() {
        val directory = createTempDir(prefix = "cloud-asr-pending-private")
        try {
            val identity = "https://example.invalid/private/video?token=secret"
            val digest = "d".repeat(64)
            val marker = CloudAsrSubmissionGuardStore.fileFor(directory, "elevenlabs", identity, digest)
            assertFalse(marker.name.contains("example"))
            assertFalse(marker.name.contains("secret"))
            assertFalse(marker.name.contains(digest))
        } finally { directory.deleteRecursively() }
    }
}
