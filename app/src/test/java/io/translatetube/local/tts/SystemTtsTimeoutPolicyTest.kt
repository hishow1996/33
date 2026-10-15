package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTtsTimeoutPolicyTest {
    @Test
    fun keepsNormalSubtitleCuesAtTheExistingMinimumTimeout() {
        assertEquals(
            SystemTtsTimeoutPolicy.MIN_TIMEOUT_MS,
            SystemTtsTimeoutPolicy.timeoutMs("这是一条普通字幕。", 1.0f),
        )
    }

    @Test
    fun givesLongAndSlowCuesMoreTimeRatherThanFailingAtThirtySeconds() {
        val normal = SystemTtsTimeoutPolicy.timeoutMs("字幕".repeat(1_000), 1.0f)
        val slow = SystemTtsTimeoutPolicy.timeoutMs("字幕".repeat(1_000), 0.5f)
        assertTrue(normal > SystemTtsTimeoutPolicy.MIN_TIMEOUT_MS)
        assertTrue(slow > normal)
        assertTrue(slow <= SystemTtsTimeoutPolicy.MAX_TIMEOUT_MS)
    }

    @Test
    fun invalidSpeedsAndEmptyTextStillReceiveBoundedTimeouts() {
        assertEquals(
            SystemTtsTimeoutPolicy.timeoutMs("x", 1.0f),
            SystemTtsTimeoutPolicy.timeoutMs("x", Float.NaN),
        )
        assertEquals(
            SystemTtsTimeoutPolicy.MIN_TIMEOUT_MS,
            SystemTtsTimeoutPolicy.timeoutMs("", 2.0f),
        )
    }
}
