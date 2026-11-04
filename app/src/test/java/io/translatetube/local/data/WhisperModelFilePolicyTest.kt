package io.translatetube.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelFilePolicyTest {
    private fun files(
        encoder: Long = 80L * 1024 * 1024,
        decoder: Long = 100L * 1024 * 1024,
        tokens: Long = 1024L,
    ) = mapOf(
        "base-encoder.int8.onnx" to encoder,
        "base-decoder.int8.onnx" to decoder,
        "base-tokens.txt" to tokens,
    )

    @Test
    fun requiresEncoderDecoderAndTheExactTokenFilenameUsedByWhisperEngine() {
        assertTrue(WhisperModelFilePolicy.isCoreComplete(files()))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(tokens = 0L)))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(encoder = 0L)))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(decoder = 0L)))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(tokens = 511L)))
    }

    @Test
    fun completeModelAlsoRequiresValidSizedVadFile() {
        assertTrue(WhisperModelFilePolicy.isComplete(files(), 643_854L))
        assertFalse(WhisperModelFilePolicy.isComplete(files(), WhisperModelFilePolicy.MIN_VAD_BYTES - 1L))
        assertFalse(WhisperModelFilePolicy.isComplete(files(tokens = 0L), 643_854L))
    }
}
