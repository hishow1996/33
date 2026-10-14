package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsAudioDownloaderTest {
    @Test
    fun choosesDefaultAudioRenditionAndResolvesRelativeAddress() {
        val master = """
            ﻿#EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,AUDIO="audio"
            video/720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=450000,AUDIO="audio"
            video/360.m3u8
        """.trimIndent()

        assertEquals(
            "https://cdn.example/path/audio/en.m3u8",
            HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/path/master.m3u8", master),
        )
    }

    @Test
    fun choosesLowestBandwidthVariantWhenAudioIsMuxed() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=300000
            low/index.m3u8
        """.trimIndent()

        assertEquals(
            "https://cdn.example/path/low/index.m3u8",
            HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/path/master.m3u8", master),
        )
    }

    @Test
    fun mediaPlaylistIsNotMistakenForAMasterPlaylist() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg-0001.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertNull(HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/index.m3u8", media))
    }
 
    @Test
    fun choosesAudioTrackFromTheSelectedVariantGroupNotAnotherDefaultGroup() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="commentary",NAME="Commentary",DEFAULT=YES,URI="audio/commentary.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="main",NAME="Main",DEFAULT=NO,AUTOSELECT=YES,URI="audio/main.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=300000,AUDIO="main"
            video/low.m3u8
        """.trimIndent()

        assertEquals(
            "https://cdn.example/path/audio/main.m3u8",
            HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/path/master.m3u8", master),
        )
    }

    @Test
    fun detectsSignedHlsManifestUrlByItsPath() {
        assertEquals(true, HlsAudioDownloader.isManifestUrl("https://cdn.example/playlist.m3u8?token=private"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("https://cdn.example/video.mp4?token=private"))
    }

}
