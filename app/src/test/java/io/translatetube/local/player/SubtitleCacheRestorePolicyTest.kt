package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCacheRestorePolicyTest {
    @Test
    fun importedTranslatedSubtitlesTakePriorityOverExistingTranslationCache() {
        assertFalse(
            SubtitleCacheRestorePolicy.shouldRestoreCachedRows(
                cacheAvailable = true,
                hasAuthoritativeImportedTranslation = true,
            ),
        )
    }

    @Test
    fun normalSubtitleFlowsKeepCacheFirstBehavior() {
        assertTrue(
            SubtitleCacheRestorePolicy.shouldRestoreCachedRows(
                cacheAvailable = true,
                hasAuthoritativeImportedTranslation = false,
            ),
        )
    }

    @Test
    fun missingCacheNeverSelectsCacheRestore() {
        assertFalse(
            SubtitleCacheRestorePolicy.shouldRestoreCachedRows(
                cacheAvailable = false,
                hasAuthoritativeImportedTranslation = false,
            ),
        )
    }
}
