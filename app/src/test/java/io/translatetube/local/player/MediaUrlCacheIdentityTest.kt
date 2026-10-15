package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlCacheIdentityTest {
    @Test
    fun rotatingAuthTokensDoNotInvalidateTheMediaCacheIdentity() {
        val first = "https://cdn.example/media/video.mp4?quality=360&token=old-token&expires=1000"
        val refreshed = "https://CDN.example:443/media/video.mp4?expires=2000&token=new-token&quality=360#player"

        assertEquals(
            MediaUrlCacheIdentity.key(first),
            MediaUrlCacheIdentity.key(refreshed),
        )
        assertFalse(MediaUrlCacheIdentity.canonicalUrl(first).contains("old-token"))
        assertFalse(MediaUrlCacheIdentity.canonicalUrl(refreshed).contains("new-token"))
    }

    @Test
    fun contentSelectingQueryParametersRemainPartOfTheIdentity() {
        val low = "https://cdn.example/media/video.mp4?itag=18&token=a"
        val high = "https://cdn.example/media/video.mp4?itag=22&token=b"

        assertNotEquals(
            MediaUrlCacheIdentity.key(low),
            MediaUrlCacheIdentity.key(high),
        )
    }

    @Test
    fun opaqueEndpointTokenIsHashedAndKeepsDifferentMediaIdentitiesSeparate() {
        val first = "https://api.example/stream?token=asset-secret-one"
        val second = "https://api.example/stream?token=asset-secret-two"
        val firstCanonical = MediaUrlCacheIdentity.canonicalUrl(first)

        assertNotEquals(MediaUrlCacheIdentity.key(first), MediaUrlCacheIdentity.key(second))
        assertFalse(firstCanonical.contains("asset-secret-one"))
        assertFalse(firstCanonical.contains("asset-secret-two"))
    }

    @Test
    fun cloudSignedParametersAreRemovedWithoutDroppingUnrelatedParameters() {
        val url = "https://cdn.example/v.m4s?track=audio&X-Amz-Signature=secret&X-Amz-Credential=account&part=3"
        val canonical = MediaUrlCacheIdentity.canonicalUrl(url)

        assertTrue(canonical.contains("track=audio"))
        assertTrue(canonical.contains("part=3"))
        assertFalse(canonical.contains("secret"))
        assertFalse(canonical.contains("Credential"))
    }

    @Test
    fun invalidPortsAreNotNormalizedIntoAValidMediaIdentity() {
        val invalid = "https://cdn.example:99999/media.mp4?token=abc"
        val zeroPort = "https://cdn.example:0/media.mp4?token=abc"
        val valid = "https://cdn.example/media.mp4?token=abc"
        assertEquals(invalid, MediaUrlCacheIdentity.canonicalUrl(invalid))
        assertEquals(zeroPort, MediaUrlCacheIdentity.canonicalUrl(zeroPort))
        assertNotEquals(MediaUrlCacheIdentity.key(valid), MediaUrlCacheIdentity.key(invalid))
    }

    @Test
    fun malformedReferrerOriginsRemainSeparatedInSessionCacheKeys() {
        val media = "https://cdn.example/video.mp4"
        val first = MediaUrlCacheIdentity.remoteSessionKey(media, "https://site.example:99999/watch", "session")
        val second = MediaUrlCacheIdentity.remoteSessionKey(media, "https://other.example:99999/watch", "session")
        assertNotEquals(first, second)
    }

    @Test
    fun queryOrderAndDefaultPortDoNotChangeIdentity() {
        val first = "https://cdn.example:443/media.mp4?b=2&a=1"
        val second = "https://cdn.example/media.mp4?a=1&b=2"

        assertEquals(
            MediaUrlCacheIdentity.canonicalUrl(first),
            MediaUrlCacheIdentity.canonicalUrl(second),
        )
    }

    @Test
    fun subtitleCacheIdentityIgnoresRotatingSignaturesButSeparatesCookieSessionsAndOrigins() {
        val urlA = "https://cdn.example/video.mp4?token=old-token&expires=1000"
        val urlB = "https://CDN.example:443/video.mp4?expires=2000&token=new-token"
        val first = MediaUrlCacheIdentity.remoteSessionKey(
            urlA,
            "https://site.example/watch?id=1",
            "session=alpha",
        )

        assertEquals(
            first,
            MediaUrlCacheIdentity.remoteSessionKey(
                urlB,
                "https://site.example/another-page",
                "session=alpha",
            ),
        )
        assertNotEquals(
            first,
            MediaUrlCacheIdentity.remoteSessionKey(
                urlB,
                "https://site.example/another-page",
                "session=beta",
            ),
        )
        assertNotEquals(
            first,
            MediaUrlCacheIdentity.remoteSessionKey(
                urlB,
                "https://other.example/watch?id=1",
                "session=alpha",
            ),
        )
        assertFalse(first.contains("old-token"))
        assertFalse(first.contains("new-token"))
        assertFalse(first.contains("session=alpha"))
    }

    @Test
    fun audioCacheIsStableForRotatingSignaturesButScopedToCookieSessionAndPageOrigin() {
        val urlA = "https://cdn.example/video.mp4?token=old"
        val urlB = "https://cdn.example/video.mp4?token=new"

        val first = MediaUrlCacheIdentity.audioKey(
            urlA,
            "https://site.example/watch?id=1",
            "session=alpha",
        )
        assertEquals(
            first,
            MediaUrlCacheIdentity.audioKey(
                urlB,
                "https://site.example/other-page",
                "session=alpha",
            ),
        )
        assertNotEquals(
            first,
            MediaUrlCacheIdentity.audioKey(
                urlB,
                "https://site.example/other-page",
                "session=beta",
            ),
        )
        assertNotEquals(
            first,
            MediaUrlCacheIdentity.audioKey(
                urlB,
                "https://different.example/watch?id=1",
                "session=alpha",
            ),
        )
        assertFalse(first.contains("session=alpha"))
        assertFalse(first.contains("old"))
        assertFalse(first.contains("new"))
    }
}
