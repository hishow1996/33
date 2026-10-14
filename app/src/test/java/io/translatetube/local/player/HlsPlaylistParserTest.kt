package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {
    @Test
    fun choosesDeclaredDefaultAudioRenditionFromMasterPlaylist() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Japanese",LANGUAGE="ja",DEFAULT=NO,AUTOSELECT=YES,URI="audio/ja.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="audio"
            video/720p.m3u8
        """.trimIndent()

        assertEquals(
            "https://media.example.test/path/audio/en.m3u8",
            HlsPlaylistParser.selectPlaylistUrl(
                "https://media.example.test/path/master.m3u8",
                master,
            )
        )
    }

    @Test
    fun choosesLowestBitrateDeclaredAudioOnlyVariantWhenNoAudioRenditionExists() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.42e01e,mp4a.40.2"
            combined/high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=96000,CODECS="mp4a.40.2"
            audio/low.m3u8
        """.trimIndent()

        assertEquals(
            "https://media.example.test/path/audio/low.m3u8",
            HlsPlaylistParser.selectPlaylistUrl(
                "https://media.example.test/path/master.m3u8",
                master,
            )
        )
    }

    @Test
    fun selectsAudioRenditionFromTheChosenVariantGroupNotAnotherGroupsDefault() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="japanese",NAME="Japanese",LANGUAGE="ja",DEFAULT=YES,AUTOSELECT=YES,URI="audio/ja.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="english",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=640000,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="english"
            video/low.m3u8
        """.trimIndent()

        assertEquals(
            "https://media.example.test/path/audio/en.m3u8",
            HlsPlaylistParser.selectPlaylistUrl(
                "https://media.example.test/path/master.m3u8",
                master,
            ),
        )
    }

    @Test
    fun keepsVariantWhenItsPreferredAudioIsInBandAndOtherGroupsHaveExternalUris() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="embedded",NAME="Main",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="external",NAME="Commentary",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/commentary.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=480000,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="embedded"
            video/main.m3u8
        """.trimIndent()

        assertEquals(
            "https://media.example.test/path/video/main.m3u8",
            HlsPlaylistParser.selectPlaylistUrl(
                "https://media.example.test/path/master.m3u8",
                master,
            ),
        )
    }

    @Test
    fun prefersTheVideoVariantsAudioGroupOverAnUnrelatedLowBitrateAudioOnlyVariant() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="main",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/main-en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=640000,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="main"
            video/main.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
            audio/commentary-only.m3u8
        """.trimIndent()

        assertEquals(
            "https://media.example.test/path/audio/main-en.m3u8",
            HlsPlaylistParser.selectPlaylistUrl(
                "https://media.example.test/path/master.m3u8",
                master,
            ),
        )
    }

    @Test
    fun avoidsVideoOnlyVariantWhenAnAudioBearingVariantIsDeclared() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=48000,CODECS="avc1.42e01e"
            video/video-only.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            audio/main.m3u8
        """.trimIndent()

        assertEquals(
            "https://media.example.test/path/audio/main.m3u8",
            HlsPlaylistParser.selectPlaylistUrl(
                "https://media.example.test/path/master.m3u8",
                master,
            ),
        )
    }

    @Test
    fun parsesVodSegmentsAndFmp4InitMapWithRelativeUris() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:5
            #EXT-X-MAP:URI="init/audio.mp4"
            #EXTINF:4.0,
            segments/0001.m4s
            #EXTINF:4.5,
            segments/0002.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsPlaylistParser.parseMediaPlaylist(
            "https://media.example.test/audios/en/index.m3u8",
            playlist,
        )
        assertEquals("https://media.example.test/audios/en/init/audio.mp4", result.initMapUrl)
        assertEquals(2, result.segments.size)
        assertEquals("https://media.example.test/audios/en/segments/0001.m4s", result.segments[0].url)
        assertEquals(4_000L, result.segments[0].durationMs)
        assertEquals(4_500L, result.segments[1].durationMs)
        assertEquals(8_500L, result.totalDurationMs)
        assertTrue(result.isFinal)
    }

    @Test
    fun refusesChangingInitializationMapInsideOnePlaylist() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init-a.mp4"
            #EXTINF:4,
            seg-1.m4s
            #EXT-X-MAP:URI="init-b.mp4"
            #EXTINF:4,
            seg-2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            HlsPlaylistParser.parseMediaPlaylist("https://example.test/video/index.m3u8", playlist)
        }
    }

    @Test
    fun refusesEncryptedLiveByteRangeAndDiscontinuousPlaylists() {
        val encrypted = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4,
            one.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val live = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            one.ts
        """.trimIndent()
        val byteRange = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-BYTERANGE:100@0
            #EXTINF:4,
            segments.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val discontinuous = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            one.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:4,
            two.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertThrows(UnsupportedOperationException::class.java) {
            HlsPlaylistParser.parseMediaPlaylist("https://example.test/a.m3u8", encrypted)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            HlsPlaylistParser.parseMediaPlaylist("https://example.test/a.m3u8", live)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            HlsPlaylistParser.parseMediaPlaylist("https://example.test/a.m3u8", byteRange)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            HlsPlaylistParser.parseMediaPlaylist("https://example.test/a.m3u8", discontinuous)
        }
    }

    @Test
    fun rejectsUnsafeProtocolsAndOversizedManifests() {
        assertThrows(IllegalArgumentException::class.java) {
            HlsPlaylistParser.resolveHttpUrl("https://example.test/a.m3u8", "file:///data/audio.ts")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HlsPlaylistParser.parseMediaPlaylist(
                "https://example.test/a.m3u8",
                "#EXTM3U\n" + " ".repeat(HlsPlaylistParser.MAX_MANIFEST_CHARS + 1)
            )
        }
    }
}
