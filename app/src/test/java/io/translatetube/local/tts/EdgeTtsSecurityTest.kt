package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsSecurityTest {
    @Test
    fun tokenUsesCurrentV2Format() {
        val token = EdgeTtsSecurity.generateSecMsGec(nowMs = 1_797_000_000_000L)
        assertTrue(token.matches(Regex("[A-F0-9]{16}-[A-F0-9]{64}")))
    }

    @Test
    fun tokenIsDeterministicWithinTheSameFiveMinuteBucket() {
        val first = EdgeTtsSecurity.generateSecMsGec(nowMs = 1_797_000_000_000L)
        val second = EdgeTtsSecurity.generateSecMsGec(nowMs = 1_797_000_299_000L)
        assertEquals(first, second)
    }
}
