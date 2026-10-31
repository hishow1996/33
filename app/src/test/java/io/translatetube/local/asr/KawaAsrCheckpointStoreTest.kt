package io.translatetube.local.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KawaAsrCheckpointStoreTest {
    private fun range() = CloudTranscription.KawaChunkRange(
        offsetBytes = 0L, sizeBytes = 64L, ownedStartMs = 0L, ownedEndMs = 2L,
    )

    @Test
    fun completedChunkIsPersistedAndRestoredWithExactMetadata() {
        val directory = createTempDir(prefix = "kawa-checkpoint-roundtrip")
        try {
            val digest = "a".repeat(64)
            val file = KawaAsrCheckpointStore.fileFor(directory, "source|config", 0, digest)
            val cues = listOf(Triple(0L, 1_000L, "hello"))
            assertTrue(KawaAsrCheckpointStore.write(file, digest, 0, range(), 2_000L, "en", cues))
            val restored = KawaAsrCheckpointStore.read(file, digest, 0, range(), 2_000L)
            assertEquals(cues, restored?.cues)
            assertEquals("en", restored?.language)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun emptySuccessfulChunkIsStillRestorable() {
        val directory = createTempDir(prefix = "kawa-checkpoint-empty")
        try {
            val digest = "b".repeat(64)
            val file = KawaAsrCheckpointStore.fileFor(directory, "source", 1, digest)
            assertTrue(KawaAsrCheckpointStore.write(file, digest, 1, range(), 2_000L, "", emptyList()))
            val restored = KawaAsrCheckpointStore.read(file, digest, 1, range(), 2_000L)
            assertTrue(restored != null)
            assertTrue(restored!!.cues.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun sourceIdentityAndChunkContentArePartOfCachePath() {
        val directory = createTempDir(prefix = "kawa-checkpoint-identity")
        try {
            val digestA = "a".repeat(64)
            val digestB = "b".repeat(64)
            val a = KawaAsrCheckpointStore.fileFor(directory, "source-a", 0, digestA)
            assertEquals(a.name, KawaAsrCheckpointStore.fileFor(directory, "source-a", 0, digestA).name)
            assertNotEquals(a.name, KawaAsrCheckpointStore.fileFor(directory, "source-a", 0, digestB).name)
            assertNotEquals(a.name, KawaAsrCheckpointStore.fileFor(directory, "source-b", 0, digestA).name)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun changedTimelineOrDurationInvalidatesACompletedChunk() {
        val directory = createTempDir(prefix = "kawa-checkpoint-stale")
        try {
            val digest = "c".repeat(64)
            val file = KawaAsrCheckpointStore.fileFor(directory, "source", 0, digest)
            KawaAsrCheckpointStore.write(file, digest, 0, range(), 2_000L, "en", listOf(Triple(0L, 1_000L, "hello")))
            assertNull(KawaAsrCheckpointStore.read(file, digest, 0, range().copy(ownedEndMs = 3L), 2_000L))
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun malformedAndOutOfRangeRowsCannotBeCommitted() {
        val directory = createTempDir(prefix = "kawa-checkpoint-invalid")
        try {
            val digest = "d".repeat(64)
            val file = KawaAsrCheckpointStore.fileFor(directory, "source", 0, digest)
            val error = runCatching {
                KawaAsrCheckpointStore.write(file, digest, 0, range(), 2_000L, "en", listOf(Triple(3L, 4L, "outside")))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun pcmRangeDigestTracksActualBytesAndValidatesFileBounds() {
        val directory = createTempDir(prefix = "kawa-checkpoint-digest")
        try {
            val pcm = File(directory, "audio.wav").apply { writeBytes(ByteArray(256) { it.toByte() }) }
            val first = KawaAsrCheckpointStore.digestAudioRange(pcm, 44L, 64L)
            assertEquals(first, KawaAsrCheckpointStore.digestAudioRange(pcm, 44L, 64L))
            java.io.RandomAccessFile(pcm, "rw").use { file -> file.seek(50L); file.write(99) }
            assertNotEquals(first, KawaAsrCheckpointStore.digestAudioRange(pcm, 44L, 64L))
            assertTrue(runCatching { KawaAsrCheckpointStore.digestAudioRange(pcm, 250L, 64L) }
                .exceptionOrNull() is IllegalArgumentException)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cleanupRemovesOnlyExpiredOwnedCheckpointsAndOldPartialFiles() {
        val directory = createTempDir(prefix = "kawa-checkpoint-cleanup")
        try {
            val now = 20_000_000_000L
            val stale = File(directory, "kawa-old.json").apply {
                writeText("{}"); setLastModified(now - KawaAsrCheckpointStore.MAX_AGE_MS - 1L)
            }
            val partial = File(directory, "kawa-checkpoint-old.part").apply {
                writeText("unfinished"); setLastModified(now - 24L * 60L * 60L * 1_000L)
            }
            val recent = File(directory, "kawa-recent.json").apply {
                writeText("{}"); setLastModified(now - 1_000L)
            }
            val unrelated = File(directory, "other.json").apply {
                writeText("{}"); setLastModified(now - KawaAsrCheckpointStore.MAX_AGE_MS - 1L)
            }
            assertEquals(2, KawaAsrCheckpointStore.cleanupStale(directory, now))
            assertFalse(stale.exists()); assertFalse(partial.exists())
            assertTrue(recent.exists()); assertTrue(unrelated.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun corruptCheckpointIsRejectedAndDeleted() {
        val directory = createTempDir(prefix = "kawa-checkpoint-corrupt")
        try {
            val digest = "e".repeat(64)
            val file = KawaAsrCheckpointStore.fileFor(directory, "source", 0, digest)
            file.writeText("{not-json")
            assertNull(KawaAsrCheckpointStore.read(file, digest, 0, range(), 2_000L))
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun pcmRangeDigestChecksCancellationBeforeReading() {
        val directory = createTempDir(prefix = "kawa-checkpoint-cancel")
        try {
            val pcm = File(directory, "audio.wav").apply { writeBytes(ByteArray(256)) }
            val error = runCatching {
                KawaAsrCheckpointStore.digestAudioRange(pcm, 0L, 128L) {
                    throw java.util.concurrent.CancellationException("cancelled")
                }
            }.exceptionOrNull()
            assertTrue(error is java.util.concurrent.CancellationException)
        } finally { directory.deleteRecursively() }
    }
}
