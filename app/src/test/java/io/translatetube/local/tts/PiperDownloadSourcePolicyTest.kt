package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperDownloadSourcePolicyTest {
    @Test
    fun triesOfficialSourceBeforeBothMirrors() {
        assertEquals(
            listOf(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/model.tar.bz2",
                "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/model.tar.bz2",
                "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/model.tar.bz2",
            ),
            PiperDownloadSourcePolicy.urls(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/model.tar.bz2"
            ),
        )
    }

    @Test
    fun keepsCustomSourceWithoutInventingMirrors() {
        assertEquals(
            listOf("https://example.invalid/model.tar.bz2"),
            PiperDownloadSourcePolicy.urls("https://example.invalid/model.tar.bz2"),
        )
        assertEquals(emptyList<String>(), PiperDownloadSourcePolicy.urls("  "))
    }

    @Test
    fun publishedSha256MustMatchButUnavailableDigestDoesNotBlockLegacyModels() {
        assertTrue(PiperDownloadSourcePolicy.checksumMatches("sha256:0123456789abcdef", "0123456789abcdef"))
        assertFalse(PiperDownloadSourcePolicy.checksumMatches("0123456789abcdef", "fedcba9876543210"))
        assertFalse(PiperDownloadSourcePolicy.checksumMatches("sha256:abc", null))
        assertTrue(PiperDownloadSourcePolicy.checksumMatches(null, null))
    }
}
