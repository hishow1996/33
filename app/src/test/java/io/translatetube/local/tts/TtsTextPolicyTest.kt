package io.translatetube.local.tts

import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextPolicyTest {
    @Test
    fun acceptsTextAtTheConfiguredCodePointLimit() {
        TtsTextPolicy.validate("中".repeat(TtsTextPolicy.MAX_CODE_POINTS))
    }

    @Test
    fun rejectsOversizedTextBeforeCallingAProvider() {
        val error = runCatching {
            TtsTextPolicy.validate("中".repeat(TtsTextPolicy.MAX_CODE_POINTS + 1))
        }.exceptionOrNull()

        assertTrue(error is TtsInputLimitException)
        assertTrue(error?.message?.contains("拆分") == true)
    }

    @Test
    fun countsSupplementaryCharactersAsOneCodePoint() {
        TtsTextPolicy.validate("😀".repeat(TtsTextPolicy.MAX_CODE_POINTS))
    }
}
