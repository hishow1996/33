package io.translatetube.local.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTempFilePolicyTest {
    @Test
    fun onlyRecognizesOldTtsWavFilesAsStale() {
        val now = 10_000_000_000L
        assertTrue(TtsTempFilePolicy.isStale("tts_abcd.wav", now - TtsTempFilePolicy.MAX_AGE_MS - 1L, now))
        assertTrue(TtsTempFilePolicy.isStale("tts_abcd.WAV", now - TtsTempFilePolicy.MAX_AGE_MS - 1L, now))
        assertFalse(TtsTempFilePolicy.isStale("tts_abcd.wav", now - 1_000L, now))
        assertFalse(TtsTempFilePolicy.isStale("other.wav", now - TtsTempFilePolicy.MAX_AGE_MS - 1L, now))
        assertFalse(TtsTempFilePolicy.isStale("tts_abcd.pcm", now - TtsTempFilePolicy.MAX_AGE_MS - 1L, now))
        assertFalse(TtsTempFilePolicy.isStale("tts_abcd.wav", now + 1L, now))
    }

    @Test
    fun cleanupDeletesOnlyOldMatchingFiles() {
        val now = 10_000_000_000L
        val directory = createTempDir(prefix = "tts-temp-policy")
        try {
            val stale = File(directory, "tts_stale.wav").apply {
                writeBytes(byteArrayOf(1, 2, 3))
                setLastModified(now - TtsTempFilePolicy.MAX_AGE_MS - 1L)
            }
            val recent = File(directory, "tts_recent.wav").apply {
                writeBytes(byteArrayOf(4, 5, 6))
                setLastModified(now - 1_000L)
            }
            val unrelated = File(directory, "other.wav").apply {
                writeBytes(byteArrayOf(7, 8, 9))
                setLastModified(now - TtsTempFilePolicy.MAX_AGE_MS - 1L)
            }

            assertEquals(1, TtsTempFilePolicy.cleanupStale(directory, now))
            assertFalse(stale.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
