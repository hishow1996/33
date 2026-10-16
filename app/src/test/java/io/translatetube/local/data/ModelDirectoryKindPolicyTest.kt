package io.translatetube.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDirectoryKindPolicyTest {
    @Test
    fun recognizesKokoroDirectoryAndItsTransactionalBackup() {
        assertTrue(ModelDirectoryKindPolicy.isTtsDirectoryName("tts"))
        assertTrue(ModelDirectoryKindPolicy.isTtsDirectoryName("tts.old"))
    }

    @Test
    fun doesNotMistakeWhisperBackupForKokoro() {
        assertFalse(ModelDirectoryKindPolicy.isTtsDirectoryName("whisper"))
        assertFalse(ModelDirectoryKindPolicy.isTtsDirectoryName("whisper.old"))
    }
}
