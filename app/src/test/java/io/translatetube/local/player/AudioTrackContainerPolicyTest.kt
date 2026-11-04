package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackContainerPolicyTest {
    @Test
    fun mapsKnownAndroidAudioMimeTypesToCompatibleContainers() {
        assertEquals(AudioTrackContainerPolicy.Container.MP4, AudioTrackContainerPolicy.choose("audio/mp4a-latm"))
        assertEquals(AudioTrackContainerPolicy.Container.MP4, AudioTrackContainerPolicy.choose("audio/3gpp"))
        assertEquals(AudioTrackContainerPolicy.Container.MP4, AudioTrackContainerPolicy.choose("audio/amr-wb"))
        assertEquals(AudioTrackContainerPolicy.Container.WEBM, AudioTrackContainerPolicy.choose("audio/opus"))
        assertEquals(AudioTrackContainerPolicy.Container.WEBM, AudioTrackContainerPolicy.choose("audio/vorbis"))
    }

    @Test
    fun unsupportedAndMalformedMimeTypesAreNotGuessedAsMp4() {
        assertNull(AudioTrackContainerPolicy.choose("audio/mpeg"))
        assertNull(AudioTrackContainerPolicy.choose("audio/flac"))
        assertNull(AudioTrackContainerPolicy.choose("video/mp4"))
        assertNull(AudioTrackContainerPolicy.choose(""))
        assertEquals(AudioTrackContainerPolicy.Container.WEBM, AudioTrackContainerPolicy.choose(" AUDIO/OPUS "))
    }
}
