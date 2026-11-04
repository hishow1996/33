package io.translatetube.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseVoiceModelFilePolicyTest {
    private fun files(
        model: Long = 226L * 1024L * 1024L,
        tokens: Long = 308L * 1024L,
    ) = mapOf(
        "model.int8.onnx" to model,
        "tokens.txt" to tokens,
    )

    @Test
    fun requiresTheSenseVoiceInt8ModelAndTokens() {
        assertTrue(SenseVoiceModelFilePolicy.isCoreComplete(files()))
        assertFalse(SenseVoiceModelFilePolicy.isCoreComplete(files(model = 0L)))
        assertFalse(SenseVoiceModelFilePolicy.isCoreComplete(files(tokens = 0L)))
    }

    @Test
    fun rejectsTruncatedModelAndTokenFiles() {
        assertFalse(
            SenseVoiceModelFilePolicy.isCoreComplete(
                files(model = SenseVoiceModelFilePolicy.MIN_MODEL_BYTES - 1L)
            )
        )
        assertFalse(
            SenseVoiceModelFilePolicy.isCoreComplete(
                files(tokens = SenseVoiceModelFilePolicy.MIN_TOKENS_BYTES - 1L)
            )
        )
    }
}
