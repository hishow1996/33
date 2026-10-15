package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerQualityPolicyTest {
    @Test
    fun prefers360pWhenAvailableInsteadOfChoosingTheHighestResolution() {
        val streams = listOf(
            "1080" to 1080,
            "720" to 720,
            "360" to 360,
            "240" to 240,
        )

        assertEquals(
            "360" to 360,
            PlayerQualityPolicy.chooseDefault(streams, { true }),
        )
    }

    @Test
    fun fallsBackToValidLowerQualityAndSkipsInvalidStreams() {
        val streams = listOf(
            "bad-url" to 240,
            "480" to 480,
            "720" to 720,
            "no-height" to 0,
        )

        assertEquals(
            "480" to 480,
            PlayerQualityPolicy.chooseDefault(streams, { it != "bad-url" }),
        )
    }

    @Test
    fun returnsNullWhenThereIsNoUsableStream() {
        assertNull(PlayerQualityPolicy.chooseDefault(listOf("bad" to 360), { false }))
    }

    @Test
    fun sourcePreflightRequiresUsableMuxedOrCompleteAdaptivePair() {
        val validUrl: (String) -> Boolean = { it.startsWith("https://") }

        org.junit.Assert.assertTrue(
            PlayerQualityPolicy.hasUsableSourceCandidate(
                muxedUrl = "https://media.example/muxed",
                adaptiveVideoUrl = null,
                audioUrl = "",
                isValidUrl = validUrl,
            ),
        )
        org.junit.Assert.assertTrue(
            PlayerQualityPolicy.hasUsableSourceCandidate(
                muxedUrl = null,
                adaptiveVideoUrl = "https://media.example/video",
                audioUrl = "https://media.example/audio",
                isValidUrl = validUrl,
            ),
        )
        org.junit.Assert.assertFalse(
            PlayerQualityPolicy.hasUsableSourceCandidate(
                muxedUrl = "not-a-url",
                adaptiveVideoUrl = "https://media.example/video",
                audioUrl = "not-a-url",
                isValidUrl = validUrl,
            ),
        )
        org.junit.Assert.assertFalse(
            PlayerQualityPolicy.hasUsableSourceCandidate(
                muxedUrl = null,
                adaptiveVideoUrl = null,
                audioUrl = "https://media.example/audio",
                isValidUrl = validUrl,
            ),
        )
    }
}
