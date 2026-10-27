package io.translatetube.local.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeStreamSelectorTest {
    @Test
    fun prefersMuxedMp4ForEachResolution() {
        val chosen = YouTubeStreamSelector.chooseVideoStreams(
            listOf(
                YouTubeVideoCandidate(360, "https://cdn.test/adaptive.webm", true, "webm", 3_000_000),
                YouTubeVideoCandidate(360, "https://cdn.test/muxed.mp4", false, "mp4", 500_000),
                YouTubeVideoCandidate(720, "https://cdn.test/720.mp4", true, "mp4", 4_000_000),
            )
        )

        assertEquals(listOf(720, 360), chosen.map { it.height })
        assertEquals("https://cdn.test/muxed.mp4", chosen[1].url)
        assertTrue(!chosen[1].videoOnly)
    }

    @Test
    fun filtersVideoOnlyQualitiesWhenNoSeparateAudioExists() {
        val chosen = YouTubeStreamSelector.chooseVideoStreams(
            listOf(
                YouTubeVideoCandidate(720, "https://cdn.test/720-only.mp4", true, "mp4", 3_000_000),
                YouTubeVideoCandidate(480, "https://cdn.test/480-muxed.mp4", false, "mp4", 1_000_000),
                YouTubeVideoCandidate(360, "https://cdn.test/360-only.mp4", true, "mp4", 500_000),
            ),
            separateAudioAvailable = false,
        )

        assertEquals(listOf(480), chosen.map { it.height })
        assertTrue(chosen.single().videoOnly.not())
    }

    @Test
    fun discardsInvalidHeightsAndMalformedOrNonHttpUrls() {
        val chosen = YouTubeStreamSelector.chooseVideoStreams(
            listOf(
                YouTubeVideoCandidate(0, "https://cdn.test/0.mp4", false, "mp4", 10),
                YouTubeVideoCandidate(360, "file:///tmp/video.mp4", false, "mp4", 10),
                YouTubeVideoCandidate(4320, "https://cdn.test/4320.mp4", false, "mp4", 10),
                YouTubeVideoCandidate(240, "not-a-url", false, "mp4", 10),
                YouTubeVideoCandidate(240, "https://cdn.test:0/zero-port.mp4", false, "mp4", 1000),
                YouTubeVideoCandidate(240, "https://cdn.test:99999/invalid-port.mp4", false, "mp4", 2000),
                YouTubeVideoCandidate(240, "https://cdn.test:/empty-port.mp4", false, "mp4", 3000),
                YouTubeVideoCandidate(240, "\nhttps://cdn.test/leading-newline.mp4", false, "mp4", 4000),
                YouTubeVideoCandidate(240, "https://cdn.test/trailing-newline.mp4\n", false, "mp4", 5000),
                YouTubeVideoCandidate(240, "https://cdn.test/c1.mp4" + 0x85.toChar(), false, "mp4", 6000),
                YouTubeVideoCandidate(240, "https://cdn.test/240.mp4", false, "mp4", 100),
            )
        )

        assertEquals(listOf(240), chosen.map { it.height })
        assertEquals("https://cdn.test/240.mp4", chosen.single().url)
    }

    @Test
    fun defaultSelects360pOrHighestAvailableLowerQualityAndAvoidsHigherQualityWhenPossible() {
        val streams = listOf(
            YouTubeVideoCandidate(720, "https://cdn.test/720", false, "mp4", 1),
            YouTubeVideoCandidate(480, "https://cdn.test/480", false, "mp4", 1),
            YouTubeVideoCandidate(240, "https://cdn.test/240", false, "mp4", 1),
        )
        assertEquals(2, YouTubeStreamSelector.defaultQualityIndex(streams))
        assertEquals(1, YouTubeStreamSelector.defaultQualityIndex(listOf(
            YouTubeVideoCandidate(480, "https://cdn.test/480", false, "mp4", 1),
            YouTubeVideoCandidate(240, "https://cdn.test/240", false, "mp4", 1),
        )))
        assertEquals(-1, YouTubeStreamSelector.defaultQualityIndex(emptyList()))
        assertEquals(0, YouTubeStreamSelector.defaultQualityIndex(listOf(
            YouTubeVideoCandidate(360, "https://cdn.test/360", false, "mp4", 1),
        )))
    }

    @Test
    fun selectsLowestHigherQualityOnlyWhenNoLowerQualityExists() {
        val streams = listOf(
            YouTubeVideoCandidate(1080, "https://cdn.test/1080", false, "mp4", 1),
            YouTubeVideoCandidate(480, "https://cdn.test/480", false, "mp4", 1),
        )
        assertEquals(1, YouTubeStreamSelector.defaultQualityIndex(streams))
    }

    @Test
    fun asrPrefersLowestKnownBitrateM4aRegardlessOfListOrder() {
        val audio = listOf(
            YouTubeAudioCandidate("https://cdn.test/high.m4a", "m4a", 256_000),
            YouTubeAudioCandidate("https://cdn.test/opus.webm", "webm", 48_000),
            YouTubeAudioCandidate("https://cdn.test/low.m4a", "m4a", 64_000),
        )
        assertEquals(2, YouTubeStreamSelector.chooseAudioStreamForAsrIndex(audio))
    }

    @Test
    fun asrPrefersExplicitOriginalTrackOverLowerBitrateDubbedTrack() {
        val audio = listOf(
            YouTubeAudioCandidate(
                "https://cdn.test/dub-en.m4a", "m4a", 32_000,
                languageTag = "en", isDubbed = true,
            ),
            YouTubeAudioCandidate(
                "https://cdn.test/original-ko.m4a", "m4a", 128_000,
                languageTag = "ko", isOriginal = true,
            ),
        )
        assertEquals(1, YouTubeStreamSelector.chooseAudioStreamForAsrIndex(audio, "ko"))
    }

    @Test
    fun asrRejectsConflictingExplicitOriginalLanguagesWithoutAUniqueHint() {
        val audio = listOf(
            YouTubeAudioCandidate(
                "https://cdn.test/original-en.m4a", "m4a", 32_000,
                languageTag = "en", isOriginal = true,
            ),
            YouTubeAudioCandidate(
                "https://cdn.test/original-ko.m4a", "m4a", 96_000,
                languageTag = "ko", isOriginal = true,
            ),
        )
        assertEquals(-1, YouTubeStreamSelector.chooseAudioStreamForAsrIndex(audio))
    }

    @Test
    fun asrUsesKnownOriginalLanguageWhenTrackTypeIsMissing() {
        val audio = listOf(
            YouTubeAudioCandidate("https://cdn.test/audio-en.m4a", "m4a", 64_000, languageTag = "en"),
            YouTubeAudioCandidate("https://cdn.test/audio-ko.m4a", "m4a", 96_000, languageTag = "ko"),
        )
        assertEquals(1, YouTubeStreamSelector.chooseAudioStreamForAsrIndex(audio, "ko"))
    }

    @Test
    fun asrRejectsAmbiguousUnmarkedLanguagesInsteadOfGuessingFromBitrate() {
        val audio = listOf(
            YouTubeAudioCandidate("https://cdn.test/audio-en.m4a", "m4a", 32_000, languageTag = "en"),
            YouTubeAudioCandidate("https://cdn.test/audio-ko.m4a", "m4a", 96_000, languageTag = "ko"),
        )
        assertEquals(-1, YouTubeStreamSelector.chooseAudioStreamForAsrIndex(audio))
    }

    @Test
    fun asrRejectsDubbedOnlyAudioWhenNoOriginalTrackIsAvailable() {
        val audio = listOf(
            YouTubeAudioCandidate(
                "https://cdn.test/dub-en.m4a", "m4a", 32_000,
                languageTag = "en", isDubbed = true,
            ),
            YouTubeAudioCandidate(
                "https://cdn.test/descriptive.m4a", "m4a", 64_000,
                languageTag = "ko", isDescriptive = true,
            ),
        )
        assertEquals(-1, YouTubeStreamSelector.chooseAudioStreamForAsrIndex(audio))
    }

    @Test
    fun asrFallsBackToLowestKnownCompatibleStreamWhenM4aIsUnavailable() {
        val audio = listOf(
            YouTubeAudioCandidate("https://cdn.test/opus-high.webm", "webm", 256_000),
            YouTubeAudioCandidate("https://cdn.test/opus-low.webm", "webm", 48_000),
            YouTubeAudioCandidate("https://cdn.test/expired", "webm", -1),
        )
        assertEquals(1, YouTubeStreamSelector.chooseAudioStreamForAsrIndex(audio))
    }

    @Test
    fun asrRejectsInvalidUrlsAndReturnsMinusOneWhenNoStreamCanBeUsed() {
        assertEquals(
            -1,
            YouTubeStreamSelector.chooseAudioStreamForAsrIndex(
                listOf(YouTubeAudioCandidate("file:///tmp/audio", "m4a", 32_000))
            ),
        )
    }

    @Test
    fun choosesM4aBeforeHigherBitrateWebmWhenAvailable() {
        val audio = YouTubeStreamSelector.chooseAudioStream(
            listOf(
                YouTubeAudioCandidate("https://cdn.test/opus", "webm", 256_000),
                YouTubeAudioCandidate("https://cdn.test/aac", "m4a", 128_000),
            )
        )
        assertEquals("https://cdn.test/aac", audio?.url)
        assertNull(YouTubeStreamSelector.chooseAudioStream(listOf(
            YouTubeAudioCandidate("file:///tmp/audio", "m4a", 256_000),
        )))
    }
}
