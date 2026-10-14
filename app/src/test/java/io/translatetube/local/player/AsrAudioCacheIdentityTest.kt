package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioCacheIdentityTest {
    @Test
    fun rotatingCommonSignatureParametersKeepsSameMediaIdentity() {
        val first = "webmedia:https://cdn.example.test/video/manifest.m3u8?videoId=abc&format=m3u8&token=old&expires=100&signature=aaa&hdnts=old-token&__gda__=old&exp=100"
        val refreshed = "webmedia:https://cdn.example.test/video/manifest.m3u8?signature=bbb&expires=999&format=m3u8&token=new&videoId=abc&hdnts=new-token&__gda__=new&exp=999"

        assertEquals(
            AsrAudioCacheIdentity.canonicalSource(first),
            AsrAudioCacheIdentity.canonicalSource(refreshed),
        )
        assertEquals(AsrAudioCacheIdentity.hash(first), AsrAudioCacheIdentity.hash(refreshed))
    }

    @Test
    fun contentSelectorsRemainPartOfIdentityWhenSigningTokensAreRemoved() {
        val firstVideo = "webmedia:https://cdn.example.test/play?videoId=one&token=secret-a"
        val secondVideo = "webmedia:https://cdn.example.test/play?videoId=two&token=secret-b"

        assertNotEquals(
            AsrAudioCacheIdentity.hash(firstVideo),
            AsrAudioCacheIdentity.hash(secondVideo),
        )
    }

    @Test
    fun canonicalizationNormalizesHostDefaultPortAndQueryOrdering() {
        val left = "webmedia:https://CDN.example.test:443/media.mp4?type=mp4&id=7&access_token=a"
        val right = "webmedia:https://cdn.example.test/media.mp4?id=7&access_token=b&type=mp4"

        assertEquals(
            "webmedia:https://cdn.example.test/media.mp4?id=7&type=mp4",
            AsrAudioCacheIdentity.canonicalSource(left),
        )
        assertEquals(
            AsrAudioCacheIdentity.canonicalSource(left),
            AsrAudioCacheIdentity.canonicalSource(right),
        )
    }

    @Test
    fun opaqueTokenOnlyEndpointKeepsTokenToAvoidCrossMediaCacheCollisions() {
        val first = "webmedia:https://cdn.example.test/play?token=media-one"
        val second = "webmedia:https://cdn.example.test/play?token=media-two"

        assertNotEquals(
            AsrAudioCacheIdentity.hash(first),
            AsrAudioCacheIdentity.hash(second),
        )
        assertEquals(
            "webmedia:https://cdn.example.test/video/seg-1.m4s",
            AsrAudioCacheIdentity.canonicalSource(
                "webmedia:https://cdn.example.test/video/seg-1.m4s?token=old",
            ),
        )
    }

    @Test
    fun identityIsAHashRatherThanAPersistableSignedUrl() {
        val signed = "webmedia:https://cdn.example.test/audio.m4a?token=private-secret&videoId=abc"
        val identity = AsrAudioCacheIdentity.hash(signed)

        assertTrue(identity.matches(Regex("[0-9a-f]{32}")))
        assertFalse(identity.contains("private-secret"))
        assertFalse(identity.contains("cdn.example.test"))
    }

    @Test
    fun youtubeIdsAndNonHttpValuesStayStable() {
        assertEquals("youtube-id-123", AsrAudioCacheIdentity.canonicalSource("youtube-id-123"))
        assertEquals(
            AsrAudioCacheIdentity.hash("youtube-id-123"),
            AsrAudioCacheIdentity.hash("youtube-id-123"),
        )
        assertEquals(
            "local:content://media/external/video/1",
            AsrAudioCacheIdentity.canonicalSource("local:content://media/external/video/1"),
        )
    }
}
