package io.translatetube.local.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAsrResponseCheckpointStoreTest {
    private fun setup(prefix: String): Pair<File, File> {
        val directory = createTempDir(prefix = prefix)
        val audio = File(directory, "audio.wav").apply {
            writeBytes(ByteArray(32 * 1024) { index -> (index % 251).toByte() })
        }
        return directory to audio
    }

    @Test
    fun rawProviderResponseRoundTripsWithExactRequestIdentity() {
        val (directory, audio) = setup("cloud-asr-roundtrip")
        try {
            val digest = CloudAsrResponseCheckpointStore.digestAudioFile(audio)
            val identity = "remote-session|source|elevenlabs-config-fingerprint"
            val file = CloudAsrResponseCheckpointStore.fileFor(directory, "elevenlabs", identity, digest)
            val response = """{"language_code":"en","words":[{"text":"hello","start":0.1,"end":0.5}]}"""
            assertTrue(CloudAsrResponseCheckpointStore.write(
                file, "elevenlabs", identity, digest, 10_000L, response,
            ))
            assertEquals(response, CloudAsrResponseCheckpointStore.read(
                file, "elevenlabs", identity, digest, 10_000L,
            ))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun audioIdentityChangesWhenTheUnderlyingUploadBytesChange() {
        val (directory, audio) = setup("cloud-asr-audio-digest")
        try {
            val first = CloudAsrResponseCheckpointStore.digestAudioFile(audio)
            java.io.RandomAccessFile(audio, "rw").use { file ->
                file.seek(100L)
                file.write(0x7f)
            }
            assertFalse(first == CloudAsrResponseCheckpointStore.digestAudioFile(audio))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun mismatchedProviderIdentityDurationOrAudioCannotReuseResponse() {
        val (directory, audio) = setup("cloud-asr-mismatch")
        try {
            val digest = CloudAsrResponseCheckpointStore.digestAudioFile(audio)
            val identity = "source|config"
            val file = CloudAsrResponseCheckpointStore.fileFor(directory, "cartesia", identity, digest)
            assertTrue(CloudAsrResponseCheckpointStore.write(
                file, "cartesia", identity, digest, 10_000L, """{"words":[]}""",
            ))
            assertNull(CloudAsrResponseCheckpointStore.read(
                file, "elevenlabs", identity, digest, 10_000L,
            ))
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun truncatedAndExpiredResponseCheckpointsAreRejected() {
        val (directory, audio) = setup("cloud-asr-invalid")
        try {
            val digest = CloudAsrResponseCheckpointStore.digestAudioFile(audio)
            val identity = "source|config"
            val file = CloudAsrResponseCheckpointStore.fileFor(directory, "elevenlabs", identity, digest)
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(CloudAsrResponseCheckpointStore.read(
                file, "elevenlabs", identity, digest, 10_000L,
            ))
            assertFalse(file.exists())
            assertTrue(CloudAsrResponseCheckpointStore.write(
                file, "elevenlabs", identity, digest, 10_000L, """{"words":[]}""",
                nowMs = 20_000_000_000L - CloudAsrResponseCheckpointStore.MAX_AGE_MS - 1L,
            ))
            assertNull(CloudAsrResponseCheckpointStore.read(
                file, "elevenlabs", identity, digest, 10_000L, nowMs = 20_000_000_000L,
            ))
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun oversizedProviderResponsesAreNotPersisted() {
        val (directory, audio) = setup("cloud-asr-oversized")
        try {
            val digest = CloudAsrResponseCheckpointStore.digestAudioFile(audio)
            val identity = "source|config"
            val file = CloudAsrResponseCheckpointStore.fileFor(directory, "cartesia", identity, digest)
            val oversized = "x".repeat(CloudAsrResponseCheckpointStore.MAX_RESPONSE_BYTES.toInt() + 1)
            assertFalse(CloudAsrResponseCheckpointStore.write(
                file, "cartesia", identity, digest, 10_000L, oversized,
            ))
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cleanupOnlyRemovesExpiredFilesOwnedByThisCheckpointStore() {
        val (directory, audio) = setup("cloud-asr-cleanup")
        try {
            val digest = CloudAsrResponseCheckpointStore.digestAudioFile(audio)
            val result = CloudAsrResponseCheckpointStore.fileFor(
                directory, "elevenlabs", "source|config", digest,
            ).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(20_000_000_000L - CloudAsrResponseCheckpointStore.MAX_AGE_MS - 1L)
            }
            val unrelated = File(directory, "unrelated.bin").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(20_000_000_000L - CloudAsrResponseCheckpointStore.MAX_AGE_MS - 1L)
            }
            assertEquals(1, CloudAsrResponseCheckpointStore.cleanupStale(directory, 20_000_000_000L))
            assertFalse(result.exists())
            assertTrue(unrelated.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun audioFingerprintCanBeCancelledBeforeHashCompletes() {
        val (directory, audio) = setup("cloud-asr-cancel")
        try {
            val error = runCatching {
                CloudAsrResponseCheckpointStore.digestAudioFile(audio) {
                    throw java.util.concurrent.CancellationException("cancelled")
                }
            }.exceptionOrNull()
            assertTrue(error is java.util.concurrent.CancellationException)
        } finally { directory.deleteRecursively() }
    }
}
