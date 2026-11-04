package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsCacheIdentityPolicyTest {
    @Test
    fun sameVoiceAndCredentialProduceStableCacheKey() {
        assertEquals(
            TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a"),
            TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a"),
        )
    }

    @Test
    fun changedCredentialsAndVoiceCannotReuseGeminiPcmCache() {
        val original = TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a")
        assertFalse(original == TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-b"))
        assertFalse(original == TtsCacheIdentityPolicy.geminiCacheKey("Puck", "secret-a"))
    }

    @Test
    fun changingGeminiModelCandidatesOrTheirPriorityChangesCacheIdentity() {
        val historicalOrder = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts",
        )
        val baseline = TtsCacheIdentityPolicy.geminiCacheKey(
            "Kore",
            "secret-a",
            historicalOrder,
        )

        // The currently shipped order preserves existing successful PCM identities.
        assertEquals(
            TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a"),
            baseline,
        )
        assertFalse(
            baseline == TtsCacheIdentityPolicy.geminiCacheKey(
                "Kore",
                "secret-a",
                listOf(historicalOrder[1], historicalOrder[0]),
            ),
        )
        assertFalse(
            baseline == TtsCacheIdentityPolicy.geminiCacheKey(
                "Kore",
                "secret-a",
                historicalOrder + "gemini-new-tts-model",
            ),
        )
        assertEquals(
            TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a", historicalOrder),
            TtsCacheIdentityPolicy.geminiCacheKey("Kore", "secret-a", historicalOrder),
        )
    }

    @Test
    fun edgeVoiceCatalogChangesCacheIdentityWithoutInvalidatingTheShippedCatalog() {
        val shippedCatalog = listOf(
            "zh-CN-XiaoxiaoNeural",
            "zh-CN-YunxiNeural",
            "zh-CN-XiaoyiNeural",
            "zh-CN-YunyangNeural",
            "zh-CN-XiaohanNeural",
            "zh-CN-XiaomengNeural",
        )
        val baseline = TtsCacheIdentityPolicy.edgeCacheKey(shippedCatalog)

        assertEquals("edge-readaloud-v2|zh-neural", baseline)
        assertEquals(baseline, TtsCacheIdentityPolicy.edgeCacheKey(shippedCatalog.toList()))
        assertFalse(
            baseline == TtsCacheIdentityPolicy.edgeCacheKey(
                shippedCatalog.toMutableList().apply { this[0] = "zh-CN-NewVoiceNeural" },
            ),
        )
        assertFalse(
            baseline == TtsCacheIdentityPolicy.edgeCacheKey(
                listOf(shippedCatalog[1], shippedCatalog[0]) + shippedCatalog.drop(2),
            ),
        )
        assertEquals(
            TtsCacheIdentityPolicy.edgeCacheKey(shippedCatalog.drop(1)),
            TtsCacheIdentityPolicy.edgeCacheKey(shippedCatalog.drop(1)),
        )
    }

    @Test
    fun cacheKeyDoesNotContainTheRawApiKey() {
        val key = TtsCacheIdentityPolicy.geminiCacheKey("Kore", "AIza-example-secret")
        assertFalse(key.contains("AIza-example-secret"))
        assertTrue(key.startsWith("gemini-tts-v3-kore-"))
    }

    @Test
    fun missingCredentialsHaveAStableNonSecretMarker() {
        assertEquals("missing", TtsCacheIdentityPolicy.credentialFingerprint(""))
        assertEquals("missing", TtsCacheIdentityPolicy.credentialFingerprint("  "))
    }

}
