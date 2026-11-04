package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemTtsPlaybackModeTest {
    @Test
    fun generatedModeIsTheSafeDefault() {
        assertEquals(SystemTtsPlaybackMode.GENERATED, SystemTtsPlaybackMode.fromPreference(null))
        assertEquals(SystemTtsPlaybackMode.GENERATED, SystemTtsPlaybackMode.fromPreference("generated"))
        assertEquals(SystemTtsPlaybackMode.GENERATED, SystemTtsPlaybackMode.fromPreference("unexpected"))
    }

    @Test
    fun instantModeCanBeSelectedWithoutChangingTheConfiguredTtsEngine() {
        assertEquals(SystemTtsPlaybackMode.INSTANT, SystemTtsPlaybackMode.fromPreference("instant"))
        assertEquals(SystemTtsPlaybackMode.INSTANT, SystemTtsPlaybackMode.fromPreference("INSTANT"))
    }
}
