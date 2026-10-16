package io.translatetube.local.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperTaskActivityPolicyTest {
    @Test
    fun protectsCacheDuringTheSmallWindowBeforeIoCounterIncrements() {
        assertTrue(PiperTaskActivityPolicy.isActive(
            stateActive = true,
            jobPendingOrRunning = true,
            downloadCount = 0,
        ))
    }

    @Test
    fun treatsQueuedAndRunningTasksAsActive() {
        assertTrue(PiperTaskActivityPolicy.isActive(
            stateActive = false,
            jobPendingOrRunning = true,
            downloadCount = 0,
        ))
        assertTrue(PiperTaskActivityPolicy.isActive(
            stateActive = false,
            jobPendingOrRunning = false,
            downloadCount = 1,
        ))
    }

    @Test
    fun inactiveTaskDoesNotBlockCacheCleanup() {
        assertFalse(PiperTaskActivityPolicy.isActive(
            stateActive = false,
            jobPendingOrRunning = false,
            downloadCount = 0,
        ))
    }
}
