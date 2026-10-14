package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBufferPolicyTest {
    @Test
    fun usesShorterBufferForLowRamOrSmallAppHeap() {
        val lowRam = PlayerBufferPolicy.select(true, 512, 2L * 1024 * 1024 * 1024)
        val smallHeap = PlayerBufferPolicy.select(false, 256, 2L * 1024 * 1024 * 1024)
        assertTrue(lowRam.lowMemoryProfile)
        assertTrue(smallHeap.lowMemoryProfile)
        assertEquals(15_000, lowRam.minBufferMs)
        assertEquals(60_000, lowRam.maxBufferMs)
    }

    @Test
    fun usesShorterBufferWhenAvailableSystemMemoryIsLow() {
        val config = PlayerBufferPolicy.select(
            isLowRamDevice = false,
            memoryClassMb = 512,
            availableMemoryBytes = 300L * 1024 * 1024,
        )
        assertTrue(config.lowMemoryProfile)
        assertEquals(60_000, config.maxBufferMs)
    }

    @Test
    fun keepsExistingBufferProfileOnHealthyDevice() {
        val config = PlayerBufferPolicy.select(
            isLowRamDevice = false,
            memoryClassMb = 384,
            availableMemoryBytes = 1_024L * 1024 * 1024,
        )
        assertFalse(config.lowMemoryProfile)
        assertEquals(30_000, config.minBufferMs)
        assertEquals(150_000, config.maxBufferMs)
        assertEquals(1_500, config.bufferForPlaybackMs)
        assertEquals(3_000, config.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun unknownAvailableMemoryDoesNotForceProfileByItself() {
        val config = PlayerBufferPolicy.select(false, 512, 0L)
        assertFalse(config.lowMemoryProfile)
    }
}
