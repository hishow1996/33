package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedWebMediaMetadataIdentityPolicyTest {
    @Test
    fun signedUrlRefreshKeepsMetadataBoundToTheSameMediaAndSession() {
        val firstUrl = "https://cdn.example/video.mp4?quality=240&token=old&expires=1000"
        val refreshedUrl = "https://CDN.example:443/video.mp4?expires=2000&token=new&quality=240"
        val referrer = "https://watch.example/video/1"
        val cookie = "session=alpha"
        val metadata = SelectedWebMediaMetadata(
            sourceIdentity = SelectedWebMediaMetadataIdentityPolicy.identity(
                firstUrl, referrer, cookie,
            ),
            audioCodecLabel = "AAC-LC",
        )

        assertTrue(
            SelectedWebMediaMetadataIdentityPolicy.matches(
                metadata, refreshedUrl, "https://watch.example/another-page", cookie,
            ),
        )
    }

    @Test
    fun differentRenditionIsRejectedEvenWhenTheResourcePathIsTheSame() {
        val referrer = "https://watch.example/video/1"
        val cookie = "session=alpha"
        val metadata = SelectedWebMediaMetadata(
            sourceIdentity = SelectedWebMediaMetadataIdentityPolicy.identity(
                "https://cdn.example/video.mp4?quality=240&token=old", referrer, cookie,
            ),
        )

        assertFalse(
            SelectedWebMediaMetadataIdentityPolicy.matches(
                metadata,
                "https://cdn.example/video.mp4?quality=720&token=new",
                referrer,
                cookie,
            ),
        )
    }

    @Test
    fun differentCookieSessionOrPageOriginCannotReuseSourceMetadata() {
        val url = "https://cdn.example/video.mp4?token=temporary"
        val metadata = SelectedWebMediaMetadata(
            sourceIdentity = SelectedWebMediaMetadataIdentityPolicy.identity(
                url, "https://watch.example/video/1", "session=alpha",
            ),
        )

        assertFalse(
            SelectedWebMediaMetadataIdentityPolicy.matches(
                metadata, url, "https://watch.example/video/1", "session=beta",
            ),
        )
        assertFalse(
            SelectedWebMediaMetadataIdentityPolicy.matches(
                metadata, url, "https://other.example/video/1", "session=alpha",
            ),
        )
    }

    @Test
    fun missingIdentityNeverAuthorizesMetadataReuse() {
        val metadata = SelectedWebMediaMetadata(audioCodecLabel = "AAC-LC")
        assertFalse(
            SelectedWebMediaMetadataIdentityPolicy.matches(
                metadata,
                "https://cdn.example/video.mp4",
                "https://watch.example/video",
                null,
            ),
        )
        assertFalse(
            SelectedWebMediaMetadataIdentityPolicy.matches(
                metadata, "", "https://watch.example/video", null,
            ),
        )
    }
}
