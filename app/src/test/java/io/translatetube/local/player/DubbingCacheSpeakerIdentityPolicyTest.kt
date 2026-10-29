package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DubbingCacheSpeakerIdentityPolicyTest {
    @Test
    fun fixedVoiceEngineIgnoresSpeakerAssignmentInCacheIdentity() {
        val speakerA = DubbingCacheSpeakerIdentityPolicy.cacheSpeakerId(
            speakerId = 1,
            speakerAffectsOutput = false,
        )
        val speakerB = DubbingCacheSpeakerIdentityPolicy.cacheSpeakerId(
            speakerId = 8,
            speakerAffectsOutput = false,
        )
        assertEquals(-1, speakerA)
        assertEquals(speakerA, speakerB)

        val cacheA = cacheKey(speakerA)
        val cacheB = cacheKey(speakerB)
        assertEquals(cacheA, cacheB)
    }

    @Test
    fun voiceCapableEngineKeepsSpeakerAssignmentsIsolated() {
        val speakerA = DubbingCacheSpeakerIdentityPolicy.cacheSpeakerId(
            speakerId = 1,
            speakerAffectsOutput = true,
        )
        val speakerB = DubbingCacheSpeakerIdentityPolicy.cacheSpeakerId(
            speakerId = 8,
            speakerAffectsOutput = true,
        )
        assertEquals(1, speakerA)
        assertEquals(8, speakerB)
        assertNotEquals(cacheKey(speakerA), cacheKey(speakerB))
    }

    private fun cacheKey(speakerId: Int): String = DubbingCacheIdentity.fileName(
        lineIndex = 3,
        speakerId = speakerId,
        engineKey = "fixed-voice-tts-v1",
        text = "你好",
        speed = 1f,
        cueDurationMs = 1_400L,
    )
}
