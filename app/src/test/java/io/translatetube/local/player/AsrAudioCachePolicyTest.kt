package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioCachePolicyTest {
    @Test
    fun reusesValidAudioWithinSevenDayWindow() {
        val now = 10_000_000L
        assertTrue(
            AsrAudioCachePolicy.canReuse(
                true, 40L * 1024 * 1024,
                now - 24L * 60 * 60 * 1000, now,
            )
        )
    }

    @Test
    fun rejectsMissingEmptyOversizedExpiredAndFutureDatedFiles() {
        val now = 20_000_000L
        assertFalse(AsrAudioCachePolicy.canReuse(false, 100L, now - 1, now))
        assertFalse(AsrAudioCachePolicy.canReuse(true, 0L, now - 1, now))
        assertFalse(AsrAudioCachePolicy.canReuse(true, AsrAudioCachePolicy.MAX_AUDIO_BYTES + 1, now - 1, now))
        assertFalse(AsrAudioCachePolicy.canReuse(true, 100L, now - AsrAudioCachePolicy.TTL_MS - 1, now))
        assertFalse(AsrAudioCachePolicy.canReuse(true, 100L, now + 1, now))
    }
}
