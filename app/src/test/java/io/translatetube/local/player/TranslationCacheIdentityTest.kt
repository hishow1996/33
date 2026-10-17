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
    fun streamingImportedFingerprintPreservesTheLegacyCacheKeyFormat() {
        assertEquals(
            "video-key|zh|42|imported-9da69ad2b4f33432cd74f96c",
            TranslationCacheIdentity.importedKey("video-key", 42L, source),
        )
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
    fun reselectingProviderRekeysImportedCacheWithoutLosingSourceFingerprint() {
        val originalKey = TranslationCacheIdentity.importedKey("video-key", 42L, source)
        val expectedFingerprint = originalKey.substringAfterLast("|imported-")

        val rekeyed = TranslationCacheIdentity.forProvider(
            baseKey = "unused-new-base",
            activeId = 43L,
            importedLines = null,
            previousKey = originalKey,
        )

        assertEquals("video-key|zh|43|imported-$expectedFingerprint", rekeyed)
    }

    @Test
    fun providerSnapshotUsesSelectedIdForOrdinaryResume() {
        val rekeyed = TranslationCacheIdentity.forProvider(
            baseKey = "video-key",
            activeId = 43L,
            importedLines = null,
            previousKey = "video-key|zh|42",
        )
        assertEquals("video-key|zh|43", rekeyed)
    }

    @Test
    fun explicitImportedLinesTakePrecedenceAndChangeFingerprint() {
        val changed = source.mapIndexed { i, line -> if (i == 0) line.copy(text = "Hi") else line }
        val rekeyed = TranslationCacheIdentity.forProvider(
            baseKey = "video-key",
            activeId = 43L,
            importedLines = changed,
            previousKey = TranslationCacheIdentity.importedKey("video-key", 42L, source),
        )
        assertEquals(TranslationCacheIdentity.importedKey("video-key", 43L, changed), rekeyed)
    }

    @Test
    fun providerConfigurationChangesInvalidateCacheEvenWhenProfileIdStaysTheSame() {
        fun fingerprint(
            model: String = "model-a",
            baseUrl: String = "https://api.example/v1",
            apiKey: String = "secret-a",
        ) = TranslationCacheIdentity.providerFingerprint(
            activeId = 42L,
            providerKind = "api-profile",
            type = "OPENAI",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            region = "jp-east",
        )

        val original = fingerprint()
        assertEquals(original, fingerprint())
        assertNotEquals(original, fingerprint(model = "model-b"))
        assertNotEquals(original, fingerprint(baseUrl = "https://other.example/v1"))
        assertNotEquals(original, fingerprint(apiKey = "secret-b"))
    }

    @Test
    fun providerFingerprintNeverContainsTheApiKey() {
        val secret = "private-api-key-never-store"
        val fingerprint = TranslationCacheIdentity.providerFingerprint(
            activeId = 42L,
            providerKind = "api-profile",
            type = "OPENAI",
            baseUrl = "https://api.example/v1",
            apiKey = secret,
            model = "model-a",
        )
        assertNotEquals(secret, fingerprint)
        assertTrue(!fingerprint.contains(secret))
    }

    @Test
    fun reselectingProviderKeepsImportedSourceFingerprintButChangesProviderIdentity() {
        val originalProvider = TranslationCacheIdentity.providerFingerprint(
            42L, "api-profile", type = "OPENAI", baseUrl = "https://api.example/v1", model = "a",
        )
        val updatedProvider = TranslationCacheIdentity.providerFingerprint(
            42L, "api-profile", type = "OPENAI", baseUrl = "https://api.example/v1", model = "b",
        )
        val original = TranslationCacheIdentity.importedKey("video-key", 42L, source, originalProvider)
        val expectedSourceFingerprint = original.substringAfterLast("|imported-")

        val rekeyed = TranslationCacheIdentity.forProvider(
            baseKey = "unused-new-base",
            activeId = 42L,
            importedLines = null,
            previousKey = original,
            providerFingerprint = updatedProvider,
        )

        assertEquals(
            "video-key|zh|42|provider-$updatedProvider|imported-$expectedSourceFingerprint",
            rekeyed,
        )
        assertNotEquals(original, rekeyed)
    }

    @Test
    fun ordinaryResumeKeyIncludesTheCurrentProviderFingerprint() {
        val provider = TranslationCacheIdentity.providerFingerprint(43L, "google-hybrid")
        assertEquals(
            "video-key|zh|43|provider-$provider",
            TranslationCacheIdentity.forProvider(
                baseKey = "video-key",
                activeId = 43L,
                importedLines = null,
                previousKey = "video-key|zh|42",
                providerFingerprint = provider,
            ),
        )
    }

    @Test
    fun translationProfileAndVideoKeyArePartOfIdentity() {
        val key = TranslationCacheIdentity.importedKey("video-key", 42L, source)
        assertNotEquals(key, TranslationCacheIdentity.importedKey("video-key", 43L, source))
        assertNotEquals(key, TranslationCacheIdentity.importedKey("another-video", 42L, source))
    }
}
