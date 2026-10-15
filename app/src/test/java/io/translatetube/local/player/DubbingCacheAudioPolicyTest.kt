package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DubbingCacheAudioPolicyTest {
    @Test
    fun emptyCacheMarkerIsReusableOnlyWhenTranslationIsMissing() {
        assertEquals(false, DubbingCacheAudioPolicy.reusableState(false, null))
        assertEquals(false, DubbingCacheAudioPolicy.reusableState(false, "   "))
        assertEquals(null, DubbingCacheAudioPolicy.reusableState(false, "你好"))
    }

    @Test
    fun realAudioAndMissingCorruptCachesKeepTheirExistingSemantics() {
        assertEquals(true, DubbingCacheAudioPolicy.reusableState(true, "你好"))
        assertEquals(null, DubbingCacheAudioPolicy.reusableState(null, "你好"))
        assertEquals(null, DubbingCacheAudioPolicy.reusableState(null, null))
    }
}
