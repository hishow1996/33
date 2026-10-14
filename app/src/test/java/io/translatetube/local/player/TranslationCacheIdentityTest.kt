package io.translatetube.local.player

import io.translatetube.local.subs.SubLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCacheIdentityTest {
    private val source = listOf(
        SubLine(0, 0, 1_200, "Hello"),
        SubLine(1, 1_200, 2_800, "How are you?"),
    )

    @Test
    fun identicalImportedSubtitleContentAndProfileProduceStableCacheKey() {
        val first = TranslationCacheIdentity.importedKey("video-key", 42L, source, "OPENAI|https://api.test|model-a")
        val second = TranslationCacheIdentity.importedKey(
            "video-key", 42L, source.map { it.copy() }, "OPENAI|https://api.test|model-a"
        )
        assertEquals(first, second)
        assertTrue(first.startsWith("video-key|zh|42|profile-"))
        assertTrue(first.contains("|imported-"))
    }

    @Test
    fun changedTextOrTimingGetsDifferentCacheKey() {
        val changedText = source.mapIndexed { i, line ->
            if (i == 1) line.copy(text = "How is it going?") else line
        }
        val changedTiming = source.mapIndexed { i, line ->
            if (i == 0) line.copy(endMs = 1_250L) else line
        }
        val key = TranslationCacheIdentity.importedKey("video-key", 42L, source, "profile")
        assertNotEquals(key, TranslationCacheIdentity.importedKey("video-key", 42L, changedText, "profile"))
        assertNotEquals(key, TranslationCacheIdentity.importedKey("video-key", 42L, changedTiming, "profile"))
    }

    @Test
    fun translationProfileAndVideoKeyArePartOfIdentity() {
        val key = TranslationCacheIdentity.importedKey("video-key", 42L, source, "OPENAI|model-a")
        assertNotEquals(
            key,
            TranslationCacheIdentity.importedKey("video-key", 42L, source, "OPENAI|model-b"),
        )
        assertNotEquals(
            key,
            TranslationCacheIdentity.importedKey("video-key", 43L, source, "OPENAI|model-a"),
        )
        assertNotEquals(
            key,
            TranslationCacheIdentity.importedKey("another-video", 42L, source, "OPENAI|model-a"),
        )
    }

    @Test
    fun profileTokenDoesNotExposeRawProfileConfiguration() {
        val secret = "ciphertext-api-key-and-private-endpoint"
        val token = TranslationCacheIdentity.profileToken(8L, secret)
        assertTrue(token.isNotBlank())
        assertTrue(!token.contains(secret))
        assertNotEquals(token, TranslationCacheIdentity.profileToken(8L, "another-config"))
    }
    @Test
    fun detectsWhetherAResumeKeyMatchesTheCurrentProfileAndVideo() {
        val matching = TranslationCacheIdentity.videoKey("video-key", 42L, "provider-A")
        val imported = TranslationCacheIdentity.importedKey("video-key", 42L, source, "provider-A")
        assertTrue(TranslationCacheIdentity.matchesProfile(matching, "video-key", 42L, "provider-A"))
        assertTrue(TranslationCacheIdentity.matchesProfile(imported, "video-key", 42L, "provider-A"))
        assertNotEquals(true, TranslationCacheIdentity.matchesProfile(matching, "video-key", 42L, "provider-B"))
        assertNotEquals(true, TranslationCacheIdentity.matchesProfile(matching, "another-video", 42L, "provider-A"))
    }

}
