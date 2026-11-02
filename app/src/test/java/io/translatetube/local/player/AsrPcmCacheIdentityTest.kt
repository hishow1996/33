package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrPcmCacheIdentityTest {
    @Test
    fun localIdentityKeepsTheOriginalSuffixSoExistingCacheFilesStillMatch() {
        assertEquals(
            "local-media-v1:abc|local-asr-pcm16-v1",
            AsrPcmCacheIdentity.local("local-media-v1:abc"),
        )
    }

    @Test
    fun unverifiedOrBlankLocalMediaIsNeverCached() {
        assertNull(AsrPcmCacheIdentity.local("local-media-unverified-v1:abc"))
        assertNull(AsrPcmCacheIdentity.local(""))
    }

    @Test
    fun youtubeAndRemoteIdentitiesAreSeparateNamespaces() {
        val youtube = AsrPcmCacheIdentity.youtube("abc")
        val remote = AsrPcmCacheIdentity.remote("abc")
        assertEquals("youtube-asr-audio:abc|asr-pcm16-v1", youtube)
        assertEquals("remote-asr-audio:abc|asr-pcm16-v1", remote)
        assertNotEquals(youtube, remote)
        assertNull(AsrPcmCacheIdentity.youtube(" "))
        assertNull(AsrPcmCacheIdentity.remote(""))
    }

    @Test
    fun differentVideosGetDifferentIdentities() {
        assertNotEquals(AsrPcmCacheIdentity.youtube("a"), AsrPcmCacheIdentity.youtube("b"))
    }
}
