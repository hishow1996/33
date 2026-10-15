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
    fun acceptsShortNonEmptyClips() {
        assertTrue(YtDlpAudioPolicy.isValidDownload(true, 8_192L))
        assertTrue(YtDlpAudioPolicy.isValidDownload(true, 999_999L))
    }

    @Test
    fun rejectsEmptyMissingAndOversizedOutputs() {
        assertFalse(YtDlpAudioPolicy.isValidDownload(false, 1L))
        assertFalse(YtDlpAudioPolicy.isValidDownload(true, 0L))
        assertFalse(YtDlpAudioPolicy.isValidDownload(true, -1L))
        assertFalse(
            YtDlpAudioPolicy.isValidDownload(
                true,
                YtDlpAudioPolicy.MAX_AUDIO_BYTES + 1L,
            )
        )
    }

    @Test
    fun acceptsAudioAtConfiguredLimit() {
        assertTrue(
            YtDlpAudioPolicy.isValidDownload(
                true,
                YtDlpAudioPolicy.MAX_AUDIO_BYTES,
            )
        )
    }
}
