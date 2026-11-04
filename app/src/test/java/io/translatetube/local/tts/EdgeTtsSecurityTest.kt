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
    fun tokenMatchesCurrentUpstreamCapturedVector() {
        val token = EdgeTtsSecurity.generateSecMsGec(nowMs = 1_791_888_000_000L)
        assertEquals(
            "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            token,
        )
    }

    @Test
    fun tokenIsDeterministicWithinTheSameFiveMinuteBucket() {
        val first = EdgeTtsSecurity.generateSecMsGec(nowMs = 1_797_000_000_000L)
        val second = EdgeTtsSecurity.generateSecMsGec(nowMs = 1_797_000_299_000L)
        assertEquals(first, second)
    }
}
