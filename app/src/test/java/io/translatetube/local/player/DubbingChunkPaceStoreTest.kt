package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DubbingChunkPaceStoreTest {
    @Test
    fun pinnedPaceIsReusedSoCachedChunksKeepTheirIdentity() {
        // The globally learned pace drifted from 1.0 to 1.3 since the cache was written.
        assertEquals(1.0, DubbingChunkPaceStore.choose(pinned = 1.0, current = 1.3, forceFresh = false), 1e-9)
    }

    @Test
    fun firstUseTakesTheCurrentPace() {
        assertEquals(1.3, DubbingChunkPaceStore.choose(pinned = null, current = 1.3, forceFresh = false), 1e-9)
    }

    @Test
    fun regeneratingDubbingRefreshesThePinnedPace() {
        assertEquals(1.3, DubbingChunkPaceStore.choose(pinned = 1.0, current = 1.3, forceFresh = true), 1e-9)
    }

    @Test
    fun chosenPaceIsAlwaysWithinTheValidRange() {
        assertEquals(
            DubbingSpeechPaceCalibration.MAX_SCALE,
            DubbingChunkPaceStore.choose(pinned = 99.0, current = 1.0, forceFresh = false),
            1e-9,
        )
        assertEquals(
            DubbingSpeechPaceCalibration.DEFAULT_SCALE,
            DubbingChunkPaceStore.choose(pinned = Double.NaN, current = Double.NaN, forceFresh = false),
            1e-9,
        )
    }

    @Test
    fun keySeparatesVideoEngineAndSpeed() {
        val base = DubbingChunkPaceStore.key("youtube:a", "engine-1", 1.0f)
        assertNotEquals(base, DubbingChunkPaceStore.key("youtube:b", "engine-1", 1.0f))
        assertNotEquals(base, DubbingChunkPaceStore.key("youtube:a", "engine-2", 1.0f))
        assertNotEquals(base, DubbingChunkPaceStore.key("youtube:a", "engine-1", 1.2f))
        assertEquals(base, DubbingChunkPaceStore.key("youtube:a", "engine-1", 1.0f))
    }
}
