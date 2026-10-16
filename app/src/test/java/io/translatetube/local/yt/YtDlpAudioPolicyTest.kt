package io.translatetube.local.yt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpAudioPolicyTest {
    @Test
    fun excludesPartialControlAndSidecarFilesFromPublishCandidates() {
        assertTrue(YtDlpAudioPolicy.isCandidateOutput("audio.webm"))
        assertTrue(YtDlpAudioPolicy.isCandidateOutput("audio.m4a"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("audio.webm.part"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("audio.webm.ytdl"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("audio.json"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("audio.description"))
    }

    @Test
    fun rejectsFragmentFilesSubtitlesAndThumbnailsEvenIfTheyLookNonEmpty() {
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("audio.webm.part-Frag1"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("audio.vtt"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("thumbnail.webp"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput("audio.json"))
        assertFalse(YtDlpAudioPolicy.isCandidateOutput(".audio.webm"))
    }

    @Test
    fun acceptsKnownAudioAndVideoContainers() {
        assertTrue(YtDlpAudioPolicy.isCandidateOutput("audio.opus"))
        assertTrue(YtDlpAudioPolicy.isCandidateOutput("audio.m4a"))
        assertTrue(YtDlpAudioPolicy.isCandidateOutput("audio.mp4"))
        assertTrue(YtDlpAudioPolicy.isCandidateOutput("audio.webm"))
    }

    @Test
    fun acceptsShortNonEmptyClips() {
        assertTrue(YtDlpAudioPolicy.isValidDownload(true, 8_192L))
        assertTrue(YtDlpAudioPolicy.isValidDownload(true, 999_999L))
    }

    @Test
    fun rejectsEmptyAndMissingOutputsButAcceptsLargeAudio() {
        assertFalse(YtDlpAudioPolicy.isValidDownload(false, 1L))
        assertFalse(YtDlpAudioPolicy.isValidDownload(true, 0L))
        assertFalse(YtDlpAudioPolicy.isValidDownload(true, -1L))
        assertTrue(YtDlpAudioPolicy.isValidDownload(true, 200L * 1024L * 1024L))
        assertTrue(YtDlpAudioPolicy.isValidDownload(true, Long.MAX_VALUE))
    }
}
