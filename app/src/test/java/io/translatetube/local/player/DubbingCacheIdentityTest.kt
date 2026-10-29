package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DubbingCacheIdentityTest {
    private fun key(
        speakerId: Int = 7,
        engineKey: String = "SystemTTS|engine-a|voice-a",
        text: String = "你好",
        speed: Float = 1.0f,
        cueDurationMs: Long = 1_400L,
    ): String = DubbingCacheIdentity.fileName(
        lineIndex = 3,
        speakerId = speakerId,
        engineKey = engineKey,
        text = text,
        speed = speed,
        cueDurationMs = cueDurationMs,
    )

    @Test
    fun stagingNamesAreUniqueAcrossEngineInstancesAndRetainCleanupSuffix() {
        val target = key()
        val first = DubbingCacheIdentity.tempFileName(target, 101)
        val second = DubbingCacheIdentity.tempFileName(target, 102)

        assertNotEquals(first, second)
        org.junit.Assert.assertTrue(first.endsWith(".pcm.tmp"))
        org.junit.Assert.assertTrue(second.endsWith(".pcm.tmp"))
    }

    @Test
    fun changingTheAssignedSpeakerVoiceInvalidatesPcmCache() {
        assertNotEquals(key(speakerId = 7), key(speakerId = 8))
        assertNotEquals(key(speakerId = -1), key(speakerId = 0))
    }

    @Test
    fun engineTextSpeedAndCueWindowArePartOfCacheIdentity() {
        val baseline = key()
        assertNotEquals(baseline, key(engineKey = "SystemTTS|engine-b|voice-a"))
        assertNotEquals(baseline, key(text = "你好！"))
        assertNotEquals(baseline, key(speed = 1.25f))
        assertNotEquals(baseline, key(cueDurationMs = 900L))
    }

    @Test
    fun changingVoiceInsideOneEngineProducesSeparateCacheAndSwitchingBackMatches() {
        val voiceKore = key(engineKey = "gemini-tts-v3-kore-credential-a")
        val voicePuck = key(engineKey = "gemini-tts-v3-puck-credential-a")
        val returnedToKore = key(engineKey = "gemini-tts-v3-kore-credential-a")

        assertNotEquals(voiceKore, voicePuck)
        assertEquals(voiceKore, returnedToKore)
    }

    @Test
    fun changingEngineAndVoiceProducesSeparateCacheAndSwitchingBackMatches() {
        val google = key(engineKey = "google-tts-v2")
        val gemini = key(engineKey = "gemini-tts-v3-kore-credential-a")
        val returnedToGoogle = key(engineKey = "google-tts-v2")

        assertNotEquals(google, gemini)
        assertEquals(google, returnedToGoogle)
    }

    @Test
    fun switchingAwayAndBackRestoresTheOriginalVoiceCacheIdentity() {
        val voiceA = "gemini-tts-v3-kore-credential-a"
        val voiceB = "gemini-tts-v3-puck-credential-a"

        val original = key(engineKey = voiceA)
        val switched = key(engineKey = voiceB)
        val switchedBack = key(engineKey = voiceA)

        assertNotEquals(original, switched)
        assertEquals(original, switchedBack)
    }

    @Test
    fun identicalSynthesisInputsHaveDeterministicCacheNames() {
        assertEquals(key(), key())
    }
    @Test
    fun synthesisContentVersionInvalidatesOlderPcmCacheNames() {
        val current = key()
        org.junit.Assert.assertTrue(current.contains("v11-speed-"))
        assertNotEquals(current, current.replace("v11-speed-", "v10-speed-"))
        assertNotEquals(current, key(cueDurationMs = 1_401L))
    }

}
