package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCacheRestorePolicyTest {
    @Test
    fun importedTranslatedSubtitlesTakePriorityOverExistingTranslationCache() {
        assertFalse(
            SubtitleCacheRestorePolicy.shouldUseCachedRows(
                hasAuthoritativeImportedTranslation = true,
            ),
        )
    }

    @Test
    fun normalSubtitleFlowsKeepCacheFirstBehavior() {
        assertTrue(
            SubtitleCacheRestorePolicy.shouldUseCachedRows(
                hasAuthoritativeImportedTranslation = false,
            ),
        )
    }
}
