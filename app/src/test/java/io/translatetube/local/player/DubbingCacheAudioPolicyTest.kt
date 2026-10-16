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

    @Test
    fun cacheAudioMustMatchWhetherTheCueHasTranslatedText() {
        assertEquals(true, DubbingCacheAudioPolicy.isCompatibleWithTranslation(true, "你好"))
        assertEquals(false, DubbingCacheAudioPolicy.isCompatibleWithTranslation(false, "   "))
        assertEquals(false, DubbingCacheAudioPolicy.isCompatibleWithTranslation(false, null))
        assertEquals(false, DubbingCacheAudioPolicy.isCompatibleWithTranslation(true, ""))
        assertEquals(false, DubbingCacheAudioPolicy.isCompatibleWithTranslation(true, null))
    }

}
