package io.translatetube.local.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsMasterSummaryTest {

    private val masterUrl = "https://cdn.example/hls/master.m3u8"

    private val masterWithSeparateAudio = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="中文",LANGUAGE="zh",DEFAULT=YES,CHANNELS="2",URI="audio/zh.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=NO,CHANNELS="2",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=900000,AVERAGE-BANDWIDTH=600000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
        v360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,AVERAGE-BANDWIDTH=4200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
        v1080/index.m3u8
    """.trimIndent()

    private val masterMuxed = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
        low/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
        mid/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120,mp4a.40.2"
        high/index.m3u8
    """.trimIndent()

    @Test
    fun attributeListKeepsCommasInsideQuotedValues() {
        val attrs = HlsMasterSummaryParser.parseAttributeList(
            """BANDWIDTH=800000,CODECS="avc1.4d401e,mp4a.40.2",RESOLUTION=640x360,AUDIO="aud"""",
        )

        assertEquals("avc1.4d401e,mp4a.40.2", attrs["CODECS"])
        assertEquals("640x360", attrs["RESOLUTION"])
        assertEquals("aud", attrs["AUDIO"])
        assertEquals("800000", attrs["BANDWIDTH"])
    }

    @Test
    fun parsesVariantsAndAudioRenditions() {
        val summary = HlsMasterSummaryParser.parse(masterWithSeparateAudio)

        assertEquals(2, summary.variants.size)
        assertEquals(600_000L, summary.variants[0].bandwidth) // AVERAGE-BANDWIDTH wins
        assertEquals("H.264", summary.variants[0].videoCodec)
        assertEquals("AAC-LC", summary.variants[0].audioCodec)
        assertEquals(2, summary.audioRenditions.size)
        assertEquals("zh", summary.audioRenditions[0].language)
        assertEquals(2, summary.audioRenditions[0].channels)
        assertTrue(summary.audioRenditions[0].isDefault)
    }

    @Test
    fun separateAudioRenditionReportsAudioTypeAndTheVideoBitrateRange() {
        val summary = HlsMasterSummaryParser.parse(masterWithSeparateAudio)

        val description = HlsMasterSummaryParser.describe(
            summary, masterUrl,
            selectedUrl = "https://cdn.example/hls/audio/zh.m3u8",
            independentAudio = true,
        )

        val info = description.info
        assertEquals("AAC-LC", info.audioCodec)
        assertEquals(2, info.audioChannels)
        assertEquals("zh", info.audioLanguage)
        assertEquals(2, info.audioTrackCount)
        assertEquals("360p–1080p H.264 声明 600 kbps–4.2 Mbps", info.videoSummary())

        val hint = WebCandidateAudioHints.forHls(description, independentAudio = true, renditionUrl = "https://cdn.example/hls/audio/zh.m3u8")
        assertEquals(WebCandidateAudioHintKind.INDEPENDENT, hint.kind)
        assertTrue(hint.message.startsWith("检测到独立音轨 AAC-LC 立体声 zh"))
        assertTrue(hint.message.contains("不需要下载视频画面"))
        assertTrue(hint.message.contains("视频 360p–1080p"))
    }

    @Test
    fun mutedVariantsDescribeTheSelectedLowBitrateRendition() {
        val summary = HlsMasterSummaryParser.parse(masterMuxed)

        val description = HlsMasterSummaryParser.describe(
            summary, masterUrl,
            selectedUrl = "https://cdn.example/hls/low/index.m3u8",
            independentAudio = false,
            declaredBandwidth = 800_000L,
            declaredResolution = "640x360",
        )

        assertFalse(description.audioOnlyVariant)
        assertEquals("360p H.264 声明 800 kbps", description.info.videoSummary())
        assertEquals("AAC-LC", description.info.audioCodec)
        assertEquals(3, description.info.videoVariantCount)

        val hint = WebCandidateAudioHints.forHls(description, independentAudio = false, renditionUrl = "https://cdn.example/hls/low/index.m3u8")
        assertEquals(WebCandidateAudioHintKind.LOW_BITRATE_MUXED, hint.kind)
        assertEquals(
            "混合音视频 · 已选择低码率版本 · 360p H.264 声明 800 kbps · 音轨 AAC-LC · 共 3 档画质",
            hint.message,
        )
    }

    @Test
    fun variantMatchFallsBackToDeclaredBandwidthWhenUrlTextDiffers() {
        val summary = HlsMasterSummaryParser.parse(masterMuxed)

        val description = HlsMasterSummaryParser.describe(
            summary, masterUrl,
            selectedUrl = "https://other.example/rewritten.m3u8",
            independentAudio = false,
            declaredBandwidth = 800_000L,
            declaredResolution = "640x360",
        )

        assertEquals("360p H.264 声明 800 kbps", description.info.videoSummary())
    }

    @Test
    fun audioOnlyVariantIsNotLabelledAsMuxedVideo() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.5"
            audio/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            video/index.m3u8
        """.trimIndent()

        val description = HlsMasterSummaryParser.describe(
            HlsMasterSummaryParser.parse(master), masterUrl,
            selectedUrl = "https://cdn.example/hls/audio/index.m3u8",
            independentAudio = false,
            declaredBandwidth = 64_000L,
        )

        assertTrue(description.audioOnlyVariant)
        assertEquals(false, description.info.hasVideo)
        assertEquals("HE-AAC", description.info.audioCodec)
        val hint = WebCandidateAudioHints.forHls(description, independentAudio = false, renditionUrl = "https://cdn.example/hls/audio/index.m3u8")
        assertEquals(WebCandidateAudioHintKind.INDEPENDENT, hint.kind)
        assertTrue(hint.message.contains("音频专用档"))
    }

    @Test
    fun brokenLinesAreSkippedInsteadOfFailingTheWholeCheck() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=abc,RESOLUTION=640x360
            broken/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            ok/index.m3u8
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
        """.trimIndent()

        val summary = HlsMasterSummaryParser.parse(master)

        assertEquals(1, summary.variants.size)
        assertEquals("ok/index.m3u8", summary.variants[0].uri)
    }

    @Test
    fun formatHelpersProduceReadableText() {
        assertEquals("800 kbps", WebMediaFormat.bitrate(800_000L))
        assertEquals("4.2 Mbps", WebMediaFormat.bitrate(4_200_000L))
        assertEquals("900 bps", WebMediaFormat.bitrate(900L))
        assertEquals("1080p", WebMediaFormat.resolutionLabel(1920, 1080))
        assertEquals("1080p", WebMediaFormat.resolutionLabel(1080, 1920)) // portrait video
        assertEquals("H.265", WebMediaFormat.codecName("hvc1.1.6.L120"))
        assertEquals("AAC-LC", WebMediaFormat.codecName("mp4a.40.2"))
        assertEquals("E-AC-3", WebMediaFormat.codecName("ec-3"))
        assertEquals("1:05:09", WebMediaFormat.duration(3909.0))
    }
}
