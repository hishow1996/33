package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperProgressPolicyTest {
    @Test
    fun roundsShortAudioUpToOneSecond() {
        assertEquals(1L, WhisperProgressPolicy.secondsCeiling(1, 16_000))
        assertEquals(1L, WhisperProgressPolicy.secondsCeiling(8_000, 16_000))
    }

    @Test
    fun roundsExactAndPartialBoundariesCorrectly() {
        assertEquals(30L, WhisperProgressPolicy.secondsCeiling(480_000, 16_000))
        assertEquals(31L, WhisperProgressPolicy.secondsCeiling(480_001, 16_000))
    }

    @Test
    fun reportsZeroOnlyForEmptyAudio() {
        assertEquals(0L, WhisperProgressPolicy.secondsCeiling(0, 16_000))
    }
}
