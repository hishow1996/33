package io.translatetube.local.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4SourceInspectorTest {

    private fun parsed(result: Mp4InspectResult): WebCandidateMediaInfo {
        assertTrue("expected Parsed but was $result", result is Mp4InspectResult.Parsed)
        return (result as Mp4InspectResult.Parsed).info
    }

    @Test
    fun faststartMovieReportsAudioTrackAndMeasuredVideoBitrate() {
        val reader = InMemoryRangeReader(Mp4TestFiles.typicalMovie(moovFirst = true))

        val info = parsed(Mp4SourceInspector.inspect(reader))

        assertEquals("MP4", info.container)
        assertEquals(true, info.hasVideo)
        assertEquals(true, info.hasAudio)
        assertEquals("H.264", info.videoCodec)
        assertEquals(1920, info.videoWidth)
        assertEquals(1080, info.videoHeight)
        // 100 samples x 10 000 bytes over 10 s = 800 000 bit/s, measured from the sample table.
        assertEquals(800_000L, info.videoBitrateBps)
        assertEquals(WebBitrateBasis.MEASURED, info.videoBitrateBasis)
        assertEquals("AAC-LC", info.audioCodec)
        assertEquals(2, info.audioChannels)
        assertEquals(44_100, info.audioSampleRate)
        assertEquals(1, info.audioTrackCount)
        assertEquals(10.0, info.durationSeconds ?: 0.0, 0.001)
        assertEquals("1080p H.264 800 kbps", info.videoSummary())
        assertEquals("AAC-LC 立体声 138 kbps", info.audioSummary())
    }

    @Test
    fun moovAtTheEndIsFoundWithoutDownloadingTheMediaData() {
        val file = Mp4TestFiles.typicalMovie(moovFirst = false, mdatBytes = 6_000_000)
        val reader = InMemoryRangeReader(file)

        val info = parsed(Mp4SourceInspector.inspect(reader))

        assertEquals(true, info.hasAudio)
        assertEquals(800_000L, info.videoBitrateBps)
        assertTrue("only a few requests expected, was ${reader.requests}", reader.requests <= 4)
        assertTrue(
            "must not download the 6 MB media data, served ${reader.bytesServed}",
            reader.bytesServed < 1_000_000L,
        )
    }

    @Test
    fun fileWithoutAnAudioTrackIsReportedAsSilent() {
        val file = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 5_000),
                Mp4TestFiles.trak("vide", 1000, 5_000, Mp4TestFiles.avc1(1280, 720), sampleSizes = List(50) { 5_000 }),
            ),
            mdatBytes = 1000,
            moovFirst = true,
        )

        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(file)))

        assertEquals(true, info.hasVideo)
        assertEquals(false, info.hasAudio)
        assertEquals(0, info.audioTrackCount)
        assertNull(info.audioSummary())
        val hint = WebCandidateAudioHints.forDirectMp4(info)
        assertEquals(WebCandidateAudioHintKind.NO_AUDIO, hint.kind)
        assertTrue(hint.message.contains("没有音轨"))
    }

    @Test
    fun audioOnlyMp4IsAnIndependentAudioTrack() {
        val file = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 60_000),
                Mp4TestFiles.trak("soun", 48_000, 2_880_000, Mp4TestFiles.mp4a(2, 48_000, 96_000), sampleSizes = List(2800) { 250 }),
            ),
            mdatBytes = 1000,
            moovFirst = true,
            brand = "M4A ",
        )

        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(file)))

        assertEquals(false, info.hasVideo)
        assertEquals(true, info.hasAudio)
        val hint = WebCandidateAudioHints.forDirectMp4(info)
        assertEquals(WebCandidateAudioHintKind.INDEPENDENT, hint.kind)
    }

    @Test
    fun declaredBitrateFromBtrtIsUsedWhenThereIsNoSampleTable() {
        val file = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 10_000),
                Mp4TestFiles.trak("vide", 1000, 10_000, Mp4TestFiles.avc1(854, 480, Mp4TestFiles.btrt(1_500_000))),
                Mp4TestFiles.trak("soun", 44_100, 441_000, Mp4TestFiles.mp4a(2, 44_100, 128_000)),
            ),
            mdatBytes = 1000,
            moovFirst = true,
        )

        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(file)))

        assertEquals(1_500_000L, info.videoBitrateBps)
        assertEquals(WebBitrateBasis.DECLARED, info.videoBitrateBasis)
        assertEquals(128_000L, info.audioBitrateBps)
        assertEquals("480p H.264 声明 1.5 Mbps", info.videoSummary())
    }

    @Test
    fun bitrateIsEstimatedFromFileSizeWhenHeaderHasNoBitrateInformation() {
        val file = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 10_000),
                Mp4TestFiles.trak("vide", 1000, 10_000, Mp4TestFiles.avc1(1280, 720)),
            ),
            mdatBytes = 1_250_000,
            moovFirst = true,
        )

        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(file)))

        assertEquals(WebBitrateBasis.ESTIMATED, info.videoBitrateBasis)
        val bitrate = info.videoBitrateBps ?: 0L
        assertTrue("about 1 Mbps expected, was $bitrate", bitrate in 990_000L..1_020_000L)
        assertTrue(info.videoSummary().orEmpty().contains("约 1.0 Mbps"))
    }

    @Test
    fun multipleAudioTracksAreCounted() {
        val file = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 10_000),
                Mp4TestFiles.trak("vide", 1000, 10_000, Mp4TestFiles.avc1(1920, 1080), sampleSizes = List(10) { 1000 }),
                Mp4TestFiles.trak("soun", 44_100, 441_000, Mp4TestFiles.mp4a(2, 44_100, 128_000)),
                Mp4TestFiles.trak("soun", 44_100, 441_000, Mp4TestFiles.mp4a(6, 44_100, 384_000)),
            ),
            mdatBytes = 100,
            moovFirst = true,
        )

        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(file)))

        assertEquals(2, info.audioTrackCount)
        assertTrue(WebCandidateAudioHints.forDirectMp4(info).message.contains("共 2 条音轨"))
    }

    @Test
    fun surroundAudioChannelsComeFromTheCodecConfiguration() {
        val aac51 = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 10_000),
                Mp4TestFiles.trak("soun", 48_000, 480_000, Mp4TestFiles.mp4a(2, 48_000, 384_000, channelConfig = 6)),
            ),
            mdatBytes = 100, moovFirst = true,
        )
        assertEquals(6, parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(aac51))).audioChannels)

        val ac3 = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 10_000),
                // acmod 7 = 3/2 front/surround, plus LFE = 5.1; rate code 14 = 384 kbit/s.
                Mp4TestFiles.trak("soun", 48_000, 480_000, Mp4TestFiles.ac3(acmod = 7, lfe = 1, rateCode = 14)),
            ),
            mdatBytes = 100, moovFirst = true,
        )
        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(ac3)))
        assertEquals("AC-3", info.audioCodec)
        assertEquals(6, info.audioChannels)
        assertEquals(384_000L, info.audioBitrateBps)
    }

    @Test
    fun otherContainersAreNamedInsteadOfBeingCalledBroken() {
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(64)
        val result = Mp4SourceInspector.inspect(InMemoryRangeReader(webm))
        assertEquals("WebM/MKV", (result as Mp4InspectResult.NotMp4).container)

        val html = "<html><body>login</body></html>".toByteArray()
        assertEquals("网页/文本", (Mp4SourceInspector.inspect(InMemoryRangeReader(html)) as Mp4InspectResult.NotMp4).container)
    }

    @Test
    fun serverWithoutRangeSupportStillWorksWhenMoovIsAtTheStart() {
        val reader = InMemoryRangeReader(Mp4TestFiles.typicalMovie(moovFirst = true), supportsRange = false)

        assertEquals(true, parsed(Mp4SourceInspector.inspect(reader)).hasAudio)
    }

    @Test
    fun serverWithoutRangeSupportCannotReachMoovAtTheEnd() {
        val reader = InMemoryRangeReader(
            Mp4TestFiles.typicalMovie(moovFirst = false, mdatBytes = 3_000_000),
            supportsRange = false,
        )

        val result = Mp4SourceInspector.inspect(reader)

        assertTrue(result is Mp4InspectResult.Unreadable)
        assertTrue((result as Mp4InspectResult.Unreadable).reason.contains("Range"))
    }

    @Test
    fun oversizedMoovThatHidesTheTracksIsReportedAsUnreadable() {
        // 9 MB of filler comes before the tracks, so the capped moov read never reaches them.
        val file = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 10_000),
                Mp4TestFiles.box("udta", ByteArray(9 * 1024 * 1024)),
                Mp4TestFiles.trak("soun", 44_100, 441_000, Mp4TestFiles.mp4a(2, 44_100, 128_000)),
            ),
            mdatBytes = 100, moovFirst = true,
        )

        val result = Mp4SourceInspector.inspect(InMemoryRangeReader(file))

        assertTrue(result is Mp4InspectResult.Unreadable)
        assertTrue((result as Mp4InspectResult.Unreadable).reason.contains("moov"))
    }

    @Test
    fun truncatedMoovDoesNotClaimThatAudioIsMissing() {
        // The video track fits inside the capped read, the audio track after the filler does not.
        val file = Mp4TestFiles.file(
            Mp4TestFiles.moov(
                Mp4TestFiles.mvhd(1000, 10_000),
                Mp4TestFiles.trak("vide", 1000, 10_000, Mp4TestFiles.avc1(1920, 1080, Mp4TestFiles.btrt(2_000_000))),
                Mp4TestFiles.box("udta", ByteArray(9 * 1024 * 1024)),
                Mp4TestFiles.trak("soun", 44_100, 441_000, Mp4TestFiles.mp4a(2, 44_100, 128_000)),
            ),
            mdatBytes = 100, moovFirst = true,
        )

        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(file)))

        assertEquals(true, info.hasVideo)
        assertNull(info.hasAudio)
        assertEquals(WebCandidateAudioHintKind.UNKNOWN, WebCandidateAudioHints.forDirectMp4(info).kind)
    }

    @Test
    fun directHintMessageNamesAudioTrackAndVideoBitrate() {
        val info = parsed(Mp4SourceInspector.inspect(InMemoryRangeReader(Mp4TestFiles.typicalMovie(moovFirst = true))))

        val hint = WebCandidateAudioHints.forDirectMp4(info)

        assertEquals(WebCandidateAudioHintKind.MUXED_DIRECT, hint.kind)
        assertEquals(
            "音画混合直链 · 音轨 AAC-LC 立体声 138 kbps · 视频 1080p H.264 800 kbps · 0:10",
            hint.message,
        )
        assertNotNull(hint.mediaInfo)
        assertEquals("800000", hint.diagnosticContext["视频码率 bps"])
        assertEquals("true", hint.diagnosticContext["是否有音轨"])
    }
}
