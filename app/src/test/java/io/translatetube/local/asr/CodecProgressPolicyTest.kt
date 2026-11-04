package io.translatetube.local.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecProgressPolicyTest {
    @Test
    fun emptyInputBufferOnlyCountsWhenExtractorAdvances() {
        assertFalse(CodecProgressPolicy.inputMakesProgress(0, false))
        assertTrue(CodecProgressPolicy.inputMakesProgress(0, true))
        assertTrue(CodecProgressPolicy.inputMakesProgress(128, false))
    }

    @Test
    fun emptyOutputBufferOnlyCountsWhenEndOfStreamIsReached() {
        assertFalse(CodecProgressPolicy.outputMakesProgress(0, false))
        assertTrue(CodecProgressPolicy.outputMakesProgress(0, true))
        assertTrue(CodecProgressPolicy.outputMakesProgress(1, false))
    }
}
