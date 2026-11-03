package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPresentationModePolicyTest {
    @Test
    fun foregroundAndFloatingModesExpectVisibleVideo() {
        assertTrue(VideoPresentationModePolicy.shouldMonitorVideo(VideoPresentationMode.FOREGROUND))
        assertTrue(VideoPresentationModePolicy.shouldMonitorVideo(VideoPresentationMode.FLOATING))
        assertTrue(VideoPresentationModePolicy.shouldRebindVideoSurface(VideoPresentationMode.FOREGROUND))
        assertTrue(VideoPresentationModePolicy.shouldRebindVideoSurface(VideoPresentationMode.FLOATING))
    }

    @Test
    fun backgroundAudioDoesNotExpectVideoSurface() {
        assertFalse(VideoPresentationModePolicy.shouldMonitorVideo(VideoPresentationMode.BACKGROUND_AUDIO))
        assertFalse(VideoPresentationModePolicy.shouldRebindVideoSurface(VideoPresentationMode.BACKGROUND_AUDIO))
    }

    @Test
    fun floatingVideoRemainsMonitoredWhenActivityIsNotVisible() {
        // The mode is based on the actual video presentation target, not Activity visibility.
        val activityVisible = false
        val mode = if (activityVisible) {
            VideoPresentationMode.FOREGROUND
        } else {
            VideoPresentationMode.FLOATING
        }

        assertTrue(VideoPresentationModePolicy.shouldMonitorVideo(mode))
    }
}
