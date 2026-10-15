package io.translatetube.local.yt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpAudioPolicyTest {
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
