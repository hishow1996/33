package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class Small100PastCachePolicyTest {
    @Test
    fun releasesDecoderCacheBeforeEveryNextCopyAndCarriesEncoderCache() {
        val keys = Small100PastCachePolicy.keysToReleaseBeforeCopy(
            layerCount = 3,
            wasCache = true,
        )

        assertEquals(6, keys.size)
        assertEquals(
            listOf(
                "past_key_values.0.decoder.key",
                "past_key_values.0.decoder.value",
                "past_key_values.1.decoder.key",
                "past_key_values.1.decoder.value",
                "past_key_values.2.decoder.key",
                "past_key_values.2.decoder.value",
            ),
            keys,
        )
        assertEquals(0, keys.count { ".encoder." in it })
    }

    @Test
    fun releasesInitialEmptyDecoderAndEncoderPlaceholders() {
        val keys = Small100PastCachePolicy.keysToReleaseBeforeCopy(
            layerCount = 3,
            wasCache = false,
        )

        assertEquals(12, keys.size)
        assertEquals(6, keys.count { ".decoder." in it })
        assertEquals(6, keys.count { ".encoder." in it })
    }

    @Test
    fun zeroLayersHasNoCacheEntries() {
        assertEquals(
            emptyList<String>(),
            Small100PastCachePolicy.keysToReleaseBeforeCopy(layerCount = 0, wasCache = true),
        )
    }
}
