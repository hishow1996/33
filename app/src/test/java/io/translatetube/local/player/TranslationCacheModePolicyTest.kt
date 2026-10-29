package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TranslationCacheModePolicyTest {
    private fun key(
        base: String = "video-translation-provider-a",
        imported: Boolean = false,
        youtube: Boolean = false,
        asr: String = "asr-a",
    ) = TranslationCacheModePolicy.baseModeAwareKey(
        baseTranslationCacheKey = base,
        hasImportedLines = imported,
        isYouTubeCaptionSource = youtube,
        asrCacheIdentity = asr,
    )

    @Test
    fun youtubeCaptionTranslationDoesNotDependOnAsrConfiguration() {
        assertEquals(key(youtube = true, asr = "asr-a"), key(youtube = true, asr = "asr-b"))
    }

    @Test
    fun importedSubtitleTranslationDoesNotDependOnAsrConfiguration() {
        assertEquals(key(imported = true, asr = "asr-a"), key(imported = true, asr = "asr-b"))
    }

    @Test
    fun webAndLocalAsrTranslationCachesAreIsolatedByFullAsrIdentity() {
        val original = key(asr = "provider-a-config-a")
        val changed = key(asr = "provider-a-config-b")

        assertNotEquals(original, changed)
        assertEquals(
            "video-translation-provider-a|asr-provider-a-config-a-timeline-v5",
            original,
        )
    }

    @Test
    fun importedLinesTakePriorityOverYoutubeAndAsrCacheRules() {
        assertEquals(
            "video-translation-provider-a",
            key(imported = true, youtube = true, asr = "asr-a"),
        )
    }
}
