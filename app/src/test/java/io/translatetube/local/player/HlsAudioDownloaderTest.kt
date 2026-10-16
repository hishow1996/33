package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAudioDownloaderTest {


    @Test
    fun failedSegmentInstallKeepsExistingDestinationAndCompletedPart() {
        val parent = createTempDir(prefix = "hls-install-preserve")
        try {
            val part = java.io.File(parent, "segment.seg.part").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val destination = java.io.File(parent, "segment.seg").apply {
                mkdirs()
            }
            val sentinel = java.io.File(destination, "keep.txt").apply {
                writeText("previous cache must survive")
            }

            val error = runCatching {
                kotlinx.coroutines.runBlocking {
                    HlsAudioDownloader.installCompletedSegment(part, destination)
                }
            }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(destination.isDirectory)
            assertTrue(sentinel.isFile)
            assertTrue(part.isFile)
            assertEquals("previous cache must survive", sentinel.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun cleanupSegmentCacheRemovesOnlyTheMatchingMediaDirectory() {
        val parent = createTempDir(prefix = "hls-cache-cleanup")
        try {
            val destination = java.io.File(parent, "remote-audio.bin")
            val matching = java.io.File(parent, "remote-audio.segments").apply {
                mkdirs()
                java.io.File(this, "00000.seg").writeBytes(byteArrayOf(1, 2, 3))
            }
            val unrelated = java.io.File(parent, "another-audio.segments").apply {
                mkdirs()
                java.io.File(this, "00000.seg").writeBytes(byteArrayOf(4, 5, 6))
            }

            assertEquals(true, kotlinx.coroutines.runBlocking {
                HlsAudioDownloader.cleanupSegmentCache(destination)
            })
            assertEquals(false, matching.exists())
            assertEquals(true, unrelated.exists())
            assertEquals(true, kotlinx.coroutines.runBlocking {
                HlsAudioDownloader.cleanupSegmentCache(destination)
            })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun removesInheritedRangeHeadersWithoutDroppingSessionHeaders() {
        val headers = HlsAudioDownloader.headersWithoutInheritedRange(
            mapOf(
                "rAnGe" to "bytes=100-200",
                "Cookie" to "session=private",
                "Referer" to "https://page.example/watch",
            )
        )

        assertEquals(false, headers.keys.any { it.equals("Range", ignoreCase = true) })
        assertEquals("session=private", headers["Cookie"])
        assertEquals("https://page.example/watch", headers["Referer"])
    }

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
    fun parsesExplicitAndImplicitByteRangesForTheSameResource() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:1.0,
            #EXT-X-BYTERANGE:4@0
            media.bin
            #EXTINF:1.0,
            #EXT-X-BYTERANGE:4
            media.bin
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsAudioDownloader.parseMediaPlaylist(
            "https://cdn.example/path/index.m3u8",
            playlist,
        )
        val source = "https://cdn.example/path/media.bin"
        assertEquals(
            listOf(
                HlsAudioDownloader.SegmentSource(source, 0L, 4L),
                HlsAudioDownloader.SegmentSource(source, 4L, 4L),
            ),
            parsed.segments,
        )
    }

    @Test
    fun parsesByteRangeForFmp4InitializationSection() {
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="media.mp4",BYTERANGE="16@0"
            #EXTINF:1.0,
            #EXT-X-BYTERANGE:8@16
            media.mp4
            #EXTINF:1.0,
            #EXT-X-BYTERANGE:8
            media.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsAudioDownloader.parseMediaPlaylist(
            "https://cdn.example/path/index.m3u8",
            playlist,
        )
        val source = "https://cdn.example/path/media.mp4"
        assertEquals(HlsAudioDownloader.SegmentSource(source, 0L, 16L), parsed.initSegment)
        assertEquals(
            listOf(
                HlsAudioDownloader.SegmentSource(source, 16L, 8L),
                HlsAudioDownloader.SegmentSource(source, 24L, 8L),
            ),
            parsed.segments,
        )
    }

    @Test
    fun rejectsImplicitByteRangeWithoutAnImmediatelyPrecedingRangeOfTheSameResource() {
        val playlist = """
            #EXTM3U
            #EXTINF:1.0,
            #EXT-X-BYTERANGE:4
            media.bin
            #EXT-X-ENDLIST
        """.trimIndent()

        val error = runCatching {
            HlsAudioDownloader.parseMediaPlaylist(
                "https://cdn.example/path/index.m3u8",
                playlist,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("偏移量"))
    }

    @Test
    fun rejectsPartialManifestResponseWithoutARangeRequest() {
        val error = runCatching {
            HlsAudioDownloader.validateManifestResponseStatus(206)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("完整响应"))
    }

    @Test
    fun acceptsCompleteManifestResponse() {
        HlsAudioDownloader.validateManifestResponseStatus(200)
    }

    @Test
    fun rejectsPartialHttpResponseWhenNoByteRangeWasRequested() {
        val error = runCatching {
            HlsAudioDownloader.validateSegmentResponse(206, null, null, "bytes 0-99/1000")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("完整响应"))
    }

    @Test
    fun acceptsCompleteResponseAndValidatesByteRangeTotals() {
        HlsAudioDownloader.validateSegmentResponse(200, null, null, null)

        assertEquals(
            true,
            runCatching { HlsAudioDownloader.validateSegmentResponse(206, 10L, 19L, "bytes 10-19/1000") }.isSuccess,
        )
        assertEquals(
            false,
            runCatching { HlsAudioDownloader.validateSegmentResponse(206, 10L, 19L, "bytes 10-19/19") }.isSuccess,
        )
    }

    @Test
    fun detectsSignedHlsManifestUrlByItsPath() {
        assertEquals(true, HlsAudioDownloader.isManifestUrl("https://cdn.example/playlist.m3u8?token=private"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("https://cdn.example/video.mp4?token=private"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("file:///private/playlist.m3u8"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("https://cdn.example:99999/playlist.m3u8"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("https://user:pass@cdn.example/playlist.m3u8"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("\nhttps://cdn.example/playlist.m3u8"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("https://cdn.example/playlist.m3u8\n"))
        assertEquals(false, HlsAudioDownloader.isManifestUrl("https://cdn.example/playlist.m3u8" + 0x85.toChar()))
    }


    @Test
    fun rejectsEncryptedMasterPlaylistBeforeResolvingRenditions() {
        val master = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-STREAM-INF:BANDWIDTH=300000
            stream/index.m3u8
        """.trimIndent()

        val error = runCatching {
            HlsAudioDownloader.ensurePlaylistIsUnencrypted(master)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("加密"))
    }

    @Test
    fun allowsExplicitNoEncryptionMarker() {
        HlsAudioDownloader.ensurePlaylistIsUnencrypted(
            """
                #EXTM3U
                #EXT-X-KEY:METHOD=NONE
                #EXTINF:6.0,
                segment.ts
                #EXT-X-ENDLIST
            """.trimIndent()
        )
    }


    @Test
    fun rejectsEncryptionIfAnyKeyRotationUsesAnEncryptedMethod() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6.0,
            seg-1.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6.0,
            seg-2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val error = runCatching {
            HlsAudioDownloader.ensurePlaylistIsUnencrypted(playlist)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("加密"))
    }

    @Test
    fun rejectsMissingSegmentsThatWouldShiftSubtitleTimestamps() {
        val playlist = """
            #EXTM3U
            #EXTINF:6.0,
            seg-1.ts
            #EXT-X-GAP
            #EXTINF:6.0,
            seg-2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val error = runCatching {
            HlsAudioDownloader.validateNoMissingSegments(playlist)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("时间轴"))
    }


    @Test
    fun acceptsPlaylistsWithDiscontinuityTagsForTranscription() {
        // 音频转写用途容忍 EXT-X-DISCONTINUITY（片头/广告切换）：只取语音内容，
        // 片段间时间轴跳变不影响 ASR 结果，不再整体拒绝。
        val playlist = """
            #EXTM3U
            #EXTINF:6.0,
            seg-1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:6.0,
            seg-2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsAudioDownloader.parseMediaPlaylist(
            "https://cdn.example/index.m3u8",
            playlist,
        )
        assertEquals(2, parsed.segments.size)
    }

    @Test
    fun skipsVariantsWithMissingOrNonPositiveBandwidth() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=0
            invalid/zero.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=450000
            valid/360.m3u8
        """.trimIndent()

        assertEquals(
            "https://cdn.example/path/valid/360.m3u8",
            HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/path/master.m3u8", master),
        )
    }

    @Test
    fun rejectsDuplicateOrMalformedMasterPlaylistAttributes() {
        val duplicate = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=300000,BANDWIDTH=450000
            video/index.m3u8
        """.trimIndent()
        val malformed = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=300000,not-an-attribute
            video/index.m3u8
        """.trimIndent()

        assertTrue(runCatching {
            HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/master.m3u8", duplicate)
        }.exceptionOrNull()?.message.orEmpty().contains("属性"))
        assertTrue(runCatching {
            HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/master.m3u8", malformed)
        }.exceptionOrNull()?.message.orEmpty().contains("属性"))
    }

    @Test
    fun rejectsMediaSegmentsAfterEndList() {
        val playlist = """
            #EXTM3U
            #EXTINF:6.0,
            seg-1.ts
            #EXT-X-ENDLIST
            #EXTINF:6.0,
            seg-2.ts
        """.trimIndent()

        val error = runCatching {
            HlsAudioDownloader.parseMediaPlaylist("https://cdn.example/index.m3u8", playlist)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("ENDLIST"))
    }

    @Test
    fun rejectsMissingOrInvalidSegmentDurationAndLateInitializationMap() {
        val noDuration = """
            #EXTM3U
            seg-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val invalidDuration = """
            #EXTM3U
            #EXTINF:NaN,
            seg-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val lateMap = """
            #EXTM3U
            #EXTINF:6.0,
            seg-1.m4s
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg-2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        listOf(noDuration, invalidDuration, lateMap).forEach { playlist ->
            assertTrue(runCatching {
                HlsAudioDownloader.parseMediaPlaylist("https://cdn.example/index.m3u8", playlist)
            }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun rejectsEmptyMasterAttributeListAndEmptyInitializationUri() {
        val emptyMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:
            video/index.m3u8
        """.trimIndent()
        val emptyMapUri = """
            #EXTM3U
            #EXT-X-MAP:URI=""
            #EXTINF:6.0,
            seg-1.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        assertTrue(runCatching {
            HlsAudioDownloader.selectMediaPlaylistUrl("https://cdn.example/master.m3u8", emptyMaster)
        }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching {
            HlsAudioDownloader.parseMediaPlaylist("https://cdn.example/index.m3u8", emptyMapUri)
        }.exceptionOrNull() is IllegalStateException)
    }
}
