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
    fun identicalImportedSubtitleContentProducesStableCacheKey() {
        val first = TranslationCacheIdentity.importedKey("video-key", 42L, source)
        val second = TranslationCacheIdentity.importedKey("video-key", 42L, source.map { it.copy() })
        assertEquals(first, second)
        assertTrue(first.startsWith("video-key|zh|42|imported-"))
    }

    @Test
    fun changedTextOrTimingGetsDifferentCacheKey() {
        val changedText = source.mapIndexed { i, line ->
            if (i == 1) line.copy(text = "How is it going?") else line
        }
        val changedTiming = source.mapIndexed { i, line ->
            if (i == 0) line.copy(endMs = 1_250L) else line
        }
        val key = TranslationCacheIdentity.importedKey("video-key", 42L, source)
        assertNotEquals(key, TranslationCacheIdentity.importedKey("video-key", 42L, changedText))
        assertNotEquals(key, TranslationCacheIdentity.importedKey("video-key", 42L, changedTiming))
    }

    @Test
    fun translationProfileAndVideoKeyArePartOfIdentity() {
        val key = TranslationCacheIdentity.importedKey("video-key", 42L, source)
        assertNotEquals(key, TranslationCacheIdentity.importedKey("video-key", 43L, source))
        assertNotEquals(key, TranslationCacheIdentity.importedKey("another-video", 42L, source))
    }
}
