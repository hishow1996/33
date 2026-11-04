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
    fun doesNotMistakeWhisperOrSenseVoiceBackupsForKokoro() {
        assertFalse(ModelDirectoryKindPolicy.isTtsDirectoryName("whisper"))
        assertFalse(ModelDirectoryKindPolicy.isTtsDirectoryName("whisper.old"))
        assertFalse(ModelDirectoryKindPolicy.isTtsDirectoryName("sensevoice"))
        assertFalse(ModelDirectoryKindPolicy.isTtsDirectoryName("sensevoice.old"))
    }

    @Test
    fun recognizesSenseVoiceDirectoryAndItsTransactionalBackup() {
        assertTrue(ModelDirectoryKindPolicy.isSenseVoiceDirectoryName("sensevoice"))
        assertTrue(ModelDirectoryKindPolicy.isSenseVoiceDirectoryName("sensevoice.old"))
        assertFalse(ModelDirectoryKindPolicy.isSenseVoiceDirectoryName("whisper"))
    }
}
