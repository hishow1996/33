package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InProgressCheckpointLegacyCompatibilityPolicyTest {
    @Test
    fun versionSevenFallbackRowIsRestoredAsUntranslatedEvenWhenZhWasSaved() {
        assertNull(
            InProgressCheckpointLegacyCompatibilityPolicy.restoreTranslation(
                version = 7,
                rawTranslation = "你好",
                legacyOriginalAudioFallback = true,
            ),
        )
    }

    @Test
    fun versionSevenNonFallbackTranslationIsPreserved() {
        assertEquals(
            "你好",
            InProgressCheckpointLegacyCompatibilityPolicy.restoreTranslation(
                version = 7,
                rawTranslation = "你好",
                legacyOriginalAudioFallback = false,
            ),
        )
    }

    @Test
    fun versionEightIgnoresTheRemovedLegacyFlag() {
        assertEquals(
            "你好",
            InProgressCheckpointLegacyCompatibilityPolicy.restoreTranslation(
                version = 8,
                rawTranslation = "你好",
                legacyOriginalAudioFallback = true,
            ),
        )
    }

    @Test
    fun onlyVersionSevenFingerprintIncludesTheLegacyFlag() {
        assertEquals(true, InProgressCheckpointLegacyCompatibilityPolicy.includesLegacyOriginalAudioFlag(7))
        assertEquals(false, InProgressCheckpointLegacyCompatibilityPolicy.includesLegacyOriginalAudioFlag(8))
        assertEquals(false, InProgressCheckpointLegacyCompatibilityPolicy.includesLegacyOriginalAudioFlag(6))
    }
}
