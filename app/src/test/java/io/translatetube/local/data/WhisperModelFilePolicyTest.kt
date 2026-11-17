package io.translatetube.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelFilePolicyTest {
    private fun files(
        encoder: Long = 112_442_483L,
        decoder: Long = 262_226_114L,
        tokens: Long = 816_730L,
    ) = mapOf(
        "small-encoder.int8.onnx" to encoder,
        "small-decoder.int8.onnx" to decoder,
        "small-tokens.txt" to tokens,
    )

    @Test
    fun requiresEncoderDecoderAndTheExactTokenFilenameUsedByWhisperEngine() {
        assertTrue(WhisperModelFilePolicy.isCoreComplete(files()))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(tokens = 0L)))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(encoder = WhisperModelFilePolicy.MIN_ENCODER_BYTES - 1L)))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(decoder = WhisperModelFilePolicy.MIN_DECODER_BYTES - 1L)))
        assertFalse(WhisperModelFilePolicy.isCoreComplete(files(tokens = 511L)))
    }

    @Test
    fun completeModelAlsoRequiresValidSizedVadFile() {
        assertTrue(WhisperModelFilePolicy.isComplete(files(), 643_854L))
        assertFalse(WhisperModelFilePolicy.isComplete(files(), WhisperModelFilePolicy.MIN_VAD_BYTES - 1L))
        assertFalse(WhisperModelFilePolicy.isComplete(files(tokens = 0L), 643_854L))
    }
}
