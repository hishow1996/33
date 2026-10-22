package io.translatetube.local.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingTrackBusTest {

    @Test
    fun overlappingCuesAreAddedAtTheirMediaFrames() {
        val bus = DubbingTrackBus()
        bus.configure(4)
        val generation = bus.generationToken()

        assertTrue(bus.feed("first", 0L, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 4, generation))
        assertTrue(bus.feed("second", 500_000L, floatArrayOf(0.5f, 0.6f), 4, generation))

        assertArrayEquals(
            floatArrayOf(0.1f, 0.2f, 0.8f, 1.0f),
            bus.readMixWindow(0L, 4),
            0.0001f,
        )
    }

    @Test
    fun refeedingSameCueReplacesItsPreviousBlock() {
        val bus = DubbingTrackBus()
        bus.configure(4)
        val generation = bus.generationToken()

        assertTrue(bus.feed("cue", 0L, floatArrayOf(0.2f, 0.3f), 4, generation))
        assertTrue(bus.feed("cue", 0L, floatArrayOf(0.4f, 0.1f), 4, generation))

        assertArrayEquals(floatArrayOf(0.4f, 0.1f), bus.readMixWindow(0L, 2), 0.0001f)
    }

    @Test
    fun disablingDubbingClearsVoiceAndRejectsStaleFeeds() {
        val bus = DubbingTrackBus()
        bus.configure(4)
        val oldGeneration = bus.generationToken()
        assertTrue(bus.feed("cue", 0L, floatArrayOf(0.8f, 0.8f), 4, oldGeneration))

        bus.setDubbingEnabled(false)

        assertFalse(bus.feed("stale", 0L, floatArrayOf(0.5f), 4, oldGeneration))
        assertArrayEquals(floatArrayOf(0f, 0f), bus.readMixWindow(0L, 2), 0.0001f)
    }
}
